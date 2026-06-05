# CLAUDE.md

## Project Overview
A personal fitness app. An Android client connects to a self-hosted server that exposes workout planning, an AI chat assistant, and progress tracking. The AI runs locally via LM Studio and is proxied through the backend — the Android app never talks to the LLM directly.

## Architecture
```
[Android app (Kotlin/Compose)]  <-- HTTPS/REST -->  [FastAPI server]
                                                          |
                                          +---------------+----------------+
                                          |                                |
                                   [Postgres]                   [LM Studio (OpenAI-compatible API)]
```

- **Client:** Kotlin + Jetpack Compose (native Android). Room/SQLite for local cache + offline workout mode.
- **Server:** Python + FastAPI. Owns business logic, auth, and all LLM access.
- **AI:** LM Studio running locally, exposing an OpenAI-compatible endpoint (default `http://localhost:1234/v1`). FastAPI proxies to it; the model is never exposed to the network directly.
- **Database:** Postgres for the source of truth (workouts, sets, weights, reps, calendar). Android Room mirrors a working subset for offline use, syncs on reconnect.

## Tech Stack & Conventions

### Android (Kotlin/Compose)
- MVVM: `ui/` (Composables) → `viewmodel/` → `repository/` → `data/` (Room + Retrofit).
- Retrofit + OkHttp + kotlinx.serialization for the API client.
- Room for local persistence; Repository decides local-vs-remote and handles sync.
- Coroutines + Flow for async/state. No blocking calls on the main thread.
- Use `sealed interface` for UI state (Loading/Success/Error).

### Server (FastAPI)
- Layout: `app/routers/`, `app/services/`, `app/models/` (SQLAlchemy), `app/schemas/` (Pydantic).
- SQLAlchemy 2.0 style + Alembic migrations. Never edit the DB schema by hand.
- All request/response bodies validated with Pydantic; no raw dicts across boundaries.
- Async endpoints; use an async Postgres driver (asyncpg).
- Config via environment variables (`pydantic-settings`), never hardcoded.

## Core Features
1. **AI chat** — conversational workout-plan setup. Can generate either a single plan or a multi-day **program** (named days incl. rest days); the user opts in via a "Save Program" card (`POST /ai/programs/accept`), which creates the plans + program and activates it. When opened from an active workout (`ai_chat?sessionId=`), the chat is session-aware (server injects a trusted "workout in progress" block) and gives **advice only** — it does not edit the log. See AI Guardrails below.
2. **Workout mode** — per-exercise list with a target header (e.g. `8×115lb`, `3×8 BW`). Each set is a tap-to-complete control showing its reps; tapping marks it done (filled vs. dim states). Weight is logged per set beneath it and can differ across sets. Supports a "+" to add sets, bodyweight ("BW") exercises (no weight), a running session timer, per-exercise notes, and an inline edit mode. Must work offline.
3. **Calendar** — view/track scheduled and completed workouts by date.
4. **Progress tracking** — persist weight (bodyweight and/or per-exercise load) and reps over time; expose for charting.
5. **Programs** — multi-day programs (`WorkoutProgram` → ordered `ProgramDay`s, each linking a plan) with a "next day" suggestion on Home. **Preset programs** (StrongLifts 5x5, PPL, Upper/Lower, Full Body, Dumbbell-only, Bodyweight) live client-side in `ui/program/ProgramPresets.kt`; applying one resolves exercise names → ids via `GET /exercises` and reuses `POST /ai/programs/accept` to create the plans + program and activate it.
6. **Exercise library** — searchable list of seeded exercises (`/exercises`), browsable from Home.
7. **Workout helpers** — plate calculator, rest timer (with vibration), streaks, and a read-only warm-up ramp-up generator (40/60/80%) in workout mode.

## Data Model
- `User` — id, name, settings, password-reset token fields.
- `Exercise` — id, name, muscle_group, equipment.
- `WorkoutPlan` — id, user_id, name, source (manual | ai), created_at.
- `PlannedExercise` — plan_id, exercise_id, target_sets, target_reps, target_weight, is_bodyweight, order, superset_group (nullable).
- `WorkoutSession` — id, user_id, plan_id, date, status, duration_seconds, note, exercise_notes (JSON).
- `SetLog` — id, session_id, exercise_id, set_number, reps, weight (nullable for bodyweight), completed (bool), completed_at. Each set stores its own reps AND weight — they vary set-to-set (e.g. 7×125, 8×115, 8×115).
- `BodyMetric` — id, user_id, date, weight, (optional bodyfat, etc.).
- `WorkoutProgram` — id, user_id, name, is_active. `ProgramDay` — program_id, plan_id, label, order.

## AI Guardrails (critical)
The AI assists with workout planning only. The server enforces these — never rely on the client.

- **System prompt** lives server-side in `app/services/ai/prompts.py` and is never client-editable.
- **Scope:** fitness/exercise programming only. Refuse medical diagnosis, nutrition-as-medical-advice, injury treatment, supplements/PEDs dosing. Redirect users to a professional for these.
- **Safety framing:** include a non-medical-advice disclaimer; advise consulting a doctor before new programs; encourage proper form, warmups, and rest.
- **Structured output:** when generating a plan, require the model to return JSON matching the `WorkoutPlan`/`PlannedExercise` schema. Validate with Pydantic before persisting; reject and re-prompt on malformed output. A multi-day **program** is extracted the same way (`client._extract_program` → `AiProgramDraft`), reusing the shared `_resolve_exercises` resolve+clamp helper; `chat()` prefers a program when present, else falls back to a single plan. Both are extracted into `SuggestedPlan`/`SuggestedProgram` and only persisted on explicit user accept.
- **Live-session context:** when `current_session_id` is supplied, `context_service.build_current_session_context` adds a trusted summary of the in-progress workout (exercises, sets done/target, last completed set) to the system prompt. Advice-only — the AI has no path to mutate set logs.
- **Input handling:** treat user chat as untrusted. Guard against prompt injection (e.g. "ignore previous instructions") — the system prompt and validation layer take precedence.
- **Sanity bounds:** the canonical bounds live in `app/limits.py` (sets/reps/weight, plus `BODY_WEIGHT_BOUNDS_LB`/`BODYFAT_BOUNDS` for metrics) and are enforced two ways — Pydantic `Field(ge/le)` constraints on the write schemas (`PlannedExerciseIn`, `SetLogCreate/Update`, `BodyMetricCreate`) reject out-of-range client input (422), and the AI plan-extraction layer (`client._extract_plan`) *clamps* whatever the model returns into bounds rather than dropping the plan.
- **Trusted context:** `app/services/ai/context_service.build_user_context` derives a short training-history summary from the DB (recent sessions, last weights, current plan, bodyweight trend) and injects it into the system prompt as trusted context. Any client-supplied profile string is appended as stated preferences only — it never overrides the DB-derived data.
- **No tool/system access:** the LLM proxy has no file, shell, or DB write access; it only returns text/JSON that the server validates and stores.
- Keep the prompt + guardrail logic in one module (`app/services/ai/`) so it's auditable in isolation.

## API Surface
- `POST /auth/register|login|refresh|forgot-password|reset-password`
- `GET/POST /plans`, `GET /plans/{id}`, `PATCH/DELETE /plans/{id}`, `PUT /plans/{id}/exercises`
- `GET/POST /sessions`, `GET/PATCH/DELETE /sessions/{id}`, `POST/PATCH /sessions/{id}/sets[/{set_id}]`, `GET /sessions/{id}/prior-bests` (includes progression-aware `suggested_weight`)
- `POST /ai/chat` — proxies to LM Studio, applies guardrails + trusted context, returns reply (+ optional validated `suggested_plan` OR `suggested_program`). Accepts an optional `current_session_id` for in-workout, session-aware advice.
- `POST /ai/programs/accept` — persists a user-accepted AI `SuggestedProgram` (creates one plan per non-rest day + a program, activates it)
- `GET/POST /metrics/weight`
- `GET /calendar?from=&to=`
- `GET /exercises?search=`, `GET /users/me`
- `GET /progress/exercises`, `GET /progress/exercises/{id}`, `GET /progress/records` (per-exercise PRs: top weight, est. 1RM, best set volume)
- `GET/POST /programs`, `GET/PATCH/DELETE /programs/{id}`, `PUT /programs/{id}/days`, `GET /programs/active/next`

## Security
- Auth on every endpoint (token-based); no anonymous access to user data.
- **Rate limiting** (slowapi): `/auth/register` 5/min, `/auth/login` 10/min, `/auth/refresh` 10/min, forgot/reset 5/min, `/ai/chat` 20/min. Security headers added via middleware.
- LM Studio bound to localhost; only FastAPI reaches it.
- Secrets in env vars / `.env` (gitignored), never committed.
- HTTPS between Android and server: terminate TLS at a reverse proxy for real deployments. (The debug client currently allows cleartext for localhost dev — not for production.)

## Testing
- Server: pytest for routers + the AI guardrail/validation layer (mock the LLM; assert malformed/out-of-bounds output is rejected).
- Android: unit-test ViewModels and the sync/repository logic.

## Conventions for Claude
- Ask before adding new dependencies or changing the architecture above.
- Keep AI guardrail changes isolated and call them out explicitly.
- Prefer migrations over manual schema edits.
- This is a personal-use app, not a medical or clinical product — keep that scope.

## Audit Resolution Log (2026-06-03)
A full-codebase audit was run and the findings below were **fixed and verified** (server:
122 pytest passing against Postgres incl. new regression tests; Android: `:app:compileDebugKotlin`
+ `:app:testDebugUnitTest` green). Listed for traceability.

### Server (FastAPI) — fixed
- **[HIGH] Password reset token column too small.** `reset_token` widened `VARCHAR(6)` →
  `VARCHAR(64)` (model `user.py` + migration `0007`). The ~43-char `token_urlsafe(32)` now
  persists, so forgot/reset works. Covered by `tests/test_password_reset.py`.
- **[HIGH] `DELETE /plans/{id}` 500 with sessions.** `workout_sessions.plan_id` FK now
  `ON DELETE SET NULL` (model + migration `0007`); deleting a referenced plan detaches its
  sessions instead of erroring. Covered by `test_delete_plan_with_sessions_succeeds`.
- **[MED] Prompt-injection guard.** `ai/client.chat` now runs `validate_request` over every
  `role == "user"` message, not just the last. Covered by `test_injection_in_earlier_turn_blocked`.
- **[MED] `add_set` unvalidated `exercise_id`.** Now looks up the `Exercise` first and
  returns 404. Covered by `test_add_set_with_unknown_exercise_returns_404`.
- **[MED] `BodyMetric` bounds.** `weight`/`bodyfat` now use `Field(ge/le)` sourced from new
  `BODY_WEIGHT_BOUNDS_LB`/`BODYFAT_BOUNDS` in `app/limits.py`. Covered by `test_bounds.py`.
- **[LOW] Over-broad injection regexes.** `_BLOCKED_PATTERNS` rewritten to require an
  instruction-override/attack context (no more false-positives on "act as a spotter" etc.).
- **[LOW] `get_next_day` duplicate plan_ids.** Now selects the *last* matching day so the
  rotation advances from the most recent position. (A fuller fix — storing `program_day_id`
  on the session — remains possible but is deferred; see backlog.)
- **[NEW] `DELETE /sessions/{id}`** endpoint added (`delete_session`, cascades set logs),
  with ownership checks. Covered by `test_delete_session` + cross-user test.

### Android (Kotlin/Compose) — fixed
- **[HIGH] Offline-added sets vanish on reload.** `SessionRepository.getSession` now appends
  unsynced local logs (`serverId == null && syncPending`) after the server map.
- **[HIGH] Room crash on old schema.** `fallbackToDestructiveMigration()` added in
  `AppModule` (Room is a server mirror — safe to rebuild).
- **[HIGH] No reconnect sync.** `NetworkSyncObserver` (ConnectivityManager callback,
  registered in `SpotterApp.onCreate`) calls `syncPending()` on reconnect. `syncPending()`
  also gained a step that POSTs offline-added sets to already-synced sessions
  (`getUnsyncedNewLogs`), which step 3 previously skipped forever.
- **[MED] `volumeLb` truncation.** Now sums as Double and rounds once.
- **[MED] `WorkoutSummaryStore` global.** `newPrCount` moved into `WorkoutSummaryData` and
  passed as a nav route arg (survives process death). `muscleGroups` stays in the store.
- **[MED] Two-active-programs window.** `ProgramRepository.updateProgram` clears other active
  flags locally before marking the new one active.
- **[MED] `listSessions` offline fallback.** Now populates `totalSets`/`completedSets` from
  `setLogDao`.
- **[LOW] Plate calculator.** Input filter rejects multiple dots; exact float comparisons
  replaced with epsilon checks; shows the nearest-achievable total + residual.
- **[LOW] `RestTimerService`.** Now a foreground service (`startForegroundService` +
  `startForeground`, `foregroundServiceType="shortService"`); `FOREGROUND_SERVICE*` perms
  added (`POST_NOTIFICATIONS` was already declared).
- **[NEW] Delete-session UI.** `SessionHistoryScreen` has a delete action (confirm dialog) →
  `SessionHistoryViewModel.deleteSession` → repo `deleteSession` (local + `DELETE /sessions/{id}`).

### Remaining backlog (deliberately deferred — not yet done)
- **[MED][Android] Most non-workout writes have no offline path** (metrics, plans, programs,
  calendar, exercise search throw on failure; `logBodyweight` swallows the error). Needs a
  write-through + sync-queue design; out of scope for this pass.
- **[LOW][Android] `PlannedExerciseEntity` PK `(planId, exerciseId)`** forbids the same
  exercise twice in a plan. Requires a Room migration + server-aligned identity; deferred to
  avoid a risky schema change.
- **[LOW][Server] `get_next_day` exactness** — full fix is to persist the performed
  `ProgramDay` id on `WorkoutSession` (DB migration) rather than the last-match heuristic.
- **[LOW][Server] `get_exercise_progress`** takes `max(weight)`/`max(reps)` independently —
  acceptable for separate charts; revisit only if used for an est-1RM trend.

### Verified OK (not bugs)
- AI plan extraction **clamps** out-of-range model output rather than dropping the plan;
  write schemas enforce the same bounds via Pydantic. Rate limits match this doc exactly.
- All user-data endpoints require auth and filter by `user_id`; refresh-token type checks
  are correct. `estimatedOneRM` (client) matches `_epley_1rm` (server).
- Android token-refresh authenticator loop-guard, host-selection interceptor ordering, and
  the work/rest timer flow are sound.

## Sprint 2 — Features (2026-06-04)
Delivered (server: 133 pytest green; Android: `:app:testDebugUnitTest` + `assembleDebug` green):
- **Home polish:** greeting and the prominent bottom FAB both open AI chat; "+" moved to the
  top bar (create/add); bodyweight FAB is now a scale icon; upcoming blocks show "Today" /
  weekday labels and are tappable → the active program's breakdown.
- **Stats:** "This week" replaced by **Active minutes** (sum of completed-session durations,
  Mon→today); streak dedupes per day, counts a grace day (today-or-yesterday), and refreshes
  when Home resumes (so finishing a workout updates it).
- **Program breakdown:** `ProgramDetailScreen` days expand to show lifts/sets with a per-day
  **Edit** link (reuses `PlanDetail` edit). Reachable from the Home block and a new
  **Settings → Programs** section.
- **AI multi-day programs:** the AI can return a `SuggestedProgram`; user saves via
  `POST /ai/programs/accept` (auto-activates). First-run auto-generates + accepts a program
  so the calendar/Home populate out of the box. This is also the fix for "calendar shows
  nothing" — there was simply no active program before.
- **In-workout AI chat (advice-only):** chat icon in the workout top bar opens a
  session-aware chat (`ai_chat?sessionId=`); the VM resolves the local id → serverId. AI
  editing the log is intentionally deferred to a future sprint.
