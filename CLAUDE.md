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
1. **AI chat** — conversational workout-plan setup. Can generate either a single plan or a multi-day **program** (named days incl. rest days); the user opts in via a "Save Program" card (`POST /ai/programs/accept`), which creates the plans + program and activates it. When opened from an active workout (`ai_chat?sessionId=`), the chat is session-aware (server injects a trusted "workout in progress" block) and gives **advice plus user-approved adjustment cards** — it may propose swapping/adjusting/removing/adding an exercise ("I can't do bench press" → swap to DB press), which the user applies via a card with a "future workouts too" toggle (`POST /ai/sessions/{id}/adjust`). The AI never edits the log itself — it only proposes; the user taps Apply. See AI Guardrails below.
2. **Workout mode** — per-exercise list with a target header (e.g. `8×115lb`, `3×8 BW`). Each set is a tap-to-complete control showing its reps; tapping marks it done (filled vs. dim states). Weight is logged per set beneath it and can differ across sets. Supports a "+" to add sets, set **deletion**, bodyweight ("BW") exercises (no weight), a running session timer, per-exercise notes, per-set **type** (normal/warm-up/drop/failure/AMRAP — tap the set number) and optional **RPE**, adding/removing an exercise mid-session, and a rest timer with ±15s, an auto-start toggle and per-exercise overrides. Must work offline. (There is no structural "edit mode" — editing is inline per field plus the add/remove actions.)
3. **Calendar** — view/track scheduled and completed workouts by date.
4. **Progress tracking** — persist weight (bodyweight and/or per-exercise load) and reps over time; expose for charting.
5. **Programs** — multi-day programs (`WorkoutProgram` → ordered `ProgramDay`s, each linking a plan) with a "next day" suggestion on Home. **Preset programs** (StrongLifts 5x5, PPL, Upper/Lower, Full Body, Dumbbell-only, Bodyweight, **Back After a Break — Restart** and **Back After a Break — Ramp Up** (a staged returner pair, 2026-08-10), plus special-case presets: Knee-Friendly, Prenatal third-trimester, **Postpartum — First Weeks Back** and **Postpartum — Rebuilding Strength** (a staged pair), Lower-Back Friendly) live client-side in `ui/program/ProgramPresets.kt`; applying one resolves exercise names → ids via `GET /exercises` and reuses `POST /ai/programs/accept` to create the plans + program and activate it. Special-case presets avoid that case's contraindicated movement patterns and tell the user to get doctor/physio clearance in the description — they are training programs, not medical advice (consistent with the app's non-medical scope).
6. **Exercise library** — searchable list of seeded exercises (`/exercises`), reachable from **Settings → Library & data** (and from any workout card's exercise name). Each entry opens a detail screen: form instructions, primary/secondary muscles, equipment, a weight/est-1RM history chart and personal records.
7. **Workout helpers** — plate calculator, rest timer (with vibration), streaks, and a read-only warm-up ramp-up generator (40/60/80%) in workout mode.

## Data Model
- `User` — id, name, email, password-reset token fields, plus the **training profile** (`equipment`, `experience`, `goal`, `age_group`, `limitations`, `profile_updated_at`; migration `0016`, which also dropped the never-read `settings` column).
- `Exercise` — id, name, muscle_group, equipment, `instructions` (form cues), `secondary_muscles`.
- `WorkoutPlan` — id, user_id, name, source (manual | ai), created_at.
- `PlannedExercise` — plan_id, exercise_id, target_sets, target_reps, target_weight, is_bodyweight, order, superset_group (nullable).
- `WorkoutSession` — id, user_id, plan_id, date, status, duration_seconds, note, exercise_notes (JSON).
- `SetLog` — id, session_id, exercise_id, set_number, reps, weight (nullable for bodyweight), completed (bool), completed_at, `rpe` (nullable), `set_type` (`normal|warmup|drop|failure|amrap`). Each set stores its own reps AND weight — they vary set-to-set (e.g. 7×125, 8×115, 8×115). **Warm-up sets are excluded from volume, progression, est-1RM and PRs.**
- `BodyMetric` — id, user_id, date, weight, (optional bodyfat, etc.).
- `WorkoutProgram` — id, user_id, name, is_active, created_at, source (`manual|ai|preset`), description, `weeks`, `deload_week`, `started_on` (stamped on activation; drives the derived `current_week`/`is_deload_week`). `ProgramDay` — program_id, plan_id, label, order.

## AI Guardrails (critical)
The AI assists with workout planning only. The server enforces these — never rely on the client.

- **System prompt** lives server-side in `app/services/ai/prompts.py` and is never client-editable.
- **Scope:** fitness/exercise programming only. Refuse medical diagnosis, nutrition-as-medical-advice, injury treatment, supplements/PEDs dosing. Redirect users to a professional for these.
- **Safety framing:** include a non-medical-advice disclaimer; advise consulting a doctor before new programs; encourage proper form, warmups, and rest.
- **Structured output:** when generating a plan, require the model to return JSON matching the `WorkoutPlan`/`PlannedExercise` schema. Validate with Pydantic before persisting; reject and re-prompt on malformed output. A multi-day **program** is extracted the same way (`client._extract_program` → `AiProgramDraft`), reusing the shared `_resolve_exercises` resolve+clamp helper; `chat()` prefers a program when present, else falls back to a single plan. Both are extracted into `SuggestedPlan`/`SuggestedProgram` and only persisted on explicit user accept.
- **Live-session context:** when `current_session_id` is supplied, `context_service.build_current_session_context` adds a trusted summary of the in-progress workout (exercises, sets done/target, last completed set, remaining-set loads) to the system prompt.
- **Live adjustments:** with a live session in context the model MAY emit a `{"actions": [...]}` block (`swap | adjust_weight | remove | add`), extracted by `client._extract_adjustment` into `SuggestedAdjustment` — resolved against the catalog, restricted to exercises actually in the session (except `add`), clamped to `app/limits.py` bounds, capped at `MAX_ADJUSTMENT_ACTIONS` (6). Persisted ONLY via `POST /ai/sessions/{id}/adjust` on explicit user Apply (`services/ai/adjustment_apply.py`), in one transaction; **only incomplete sets are ever mutated — completed sets are immutable history**. With `apply_to_routine` it also rewrites the session's `RoutineExercise` rows so program days referencing that routine update for the future. The AI still has no autonomous write path; this adds a *suggestion type*, not a write capability.
- **Profile updates (suggestion, not a write):** when the user states a **durable** change to their setup ("I bought a squat rack"), the model MAY emit a `{"profile_update": {...}}` block, extracted by `client._extract_profile_update` into `SuggestedProfileUpdate` — each field is the complete proposed new value (null = unchanged) plus a one-line `summary`. The extractor compares against the **stored** profile and drops fields that already match, so a no-op never produces a card. Applied ONLY by the user tapping Apply on the card, which reuses the existing `PATCH /users/me/profile` (no separate apply endpoint). **Temporary situations must never propose a stored change** — "hotel gym this week" is honoured for that conversation only; that's the main failure mode and is pinned by a prompt test.
  **This is the one suggestion type outside the single-suggestion rule:** the invariant is now *exactly one **workout** suggestion (adjustment > program > plan), plus an optional profile update*. They're orthogonal — otherwise "I bought a rack, now update my program" would apply the equipment change and force the user to re-ask for the program.
- **Input handling:** treat user chat as untrusted. Guard against prompt injection (e.g. "ignore previous instructions") — the system prompt and validation layer take precedence.
- **Sanity bounds:** the canonical bounds live in `app/limits.py` (sets/reps/weight, plus `BODY_WEIGHT_BOUNDS_LB`/`BODYFAT_BOUNDS` for metrics) and are enforced two ways — Pydantic `Field(ge/le)` constraints on the write schemas (`PlannedExerciseIn`, `SetLogCreate/Update`, `BodyMetricCreate`) reject out-of-range client input (422), and the AI plan-extraction layer (`client._extract_plan`) *clamps* whatever the model returns into bounds rather than dropping the plan.
- **Trusted context:** `app/services/ai/context_service.build_user_context` injects two DB-derived blocks into the system prompt as trusted context: the user's saved **training profile** (equipment, experience, goal, age group, limitations — persisted on `users`, editable at Settings → Training profile) followed by a short training-history summary (recent sessions, last weights, current plan, bodyweight trend). Any client-supplied profile string is appended as stated preferences only — it never overrides the DB-derived data. **The profile block must survive an empty history**: it previously early-returned `None` when the user had no logged sessions, which is precisely why saved equipment never reached the coach.
- **No tool/system access:** the LLM proxy has no file, shell, or DB write access; it only returns text/JSON that the server validates and stores.
- Keep the prompt + guardrail logic in one module (`app/services/ai/`) so it's auditable in isolation.

## API Surface
- `POST /auth/register|login|refresh|forgot-password|reset-password`
- `GET/POST /routines`, `GET /routines/{id}`, `PATCH/DELETE /routines/{id}`, `PUT /routines/{id}/exercises`
- `GET/POST /sessions`, `GET/PATCH/DELETE /sessions/{id}`, `POST/PATCH /sessions/{id}/sets[/{set_id}]`, `GET /sessions/{id}/prior-bests` — the **progressive-overload engine** (`app/progression.py`, ROADMAP2 T3 #1): double progression on the routine's `target_reps` (`add_weight` only when reps are met, else `add_reps`), a stall→`deload` after 3 stuck sessions, plus best-set e1RM + a PR flag. Fields: `suggested_weight/suggested_reps/action/e1rm/is_pr` (+ legacy `suggested_reason`)
- `POST /ai/chat` — proxies to LM Studio, applies guardrails + trusted context, returns reply (+ optional validated `suggested_plan` OR `suggested_program`). Accepts an optional `current_session_id` for in-workout, session-aware advice.
- `POST /ai/programs/accept` — persists a user-accepted AI `SuggestedProgram` (creates one plan per non-rest day + a program, activates it)
- `POST /ai/sessions/{id}/adjust` — applies a user-accepted AI `SuggestedAdjustment` to a live in-progress session (swap/adjust/remove/add on incomplete sets only); `apply_to_routine` also rewrites the session's routine. Returns the updated `SessionOut`.
- `POST /ai/sessions/{id}/debrief` — post-workout coach recap of a **completed** session (409 otherwise): `{debrief}`. Read-only narration; 20/min.
- `GET /ai/recap/weekly` — the in-app week in review: `{week_start, stats{strength_sessions, cardio_sessions, total_volume_lb, active_minutes, prs, bodyweight_delta_lb}, narrative}`. **Always 200** — the stats are server-computed and `narrative` is null when LM Studio is unreachable. 5/min.
- `GET /insights` — proactive coaching signals: `{stalled[{exercise_id, exercise_name, sessions_stuck, last_weight}], prs_this_week}`. Reuses the progression engine's stall detection. 30/min.
- `GET/POST /metrics/weight`
- `GET /calendar?from=&to=`
- `GET /exercises?search=`, `GET /exercises/{id}` (name, muscle group, equipment, form `instructions`, `secondary_muscles`), `GET /users/me`
- `GET/PATCH /users/me/profile` — the persisted **training profile** (`equipment`, `experience`, `goal`, `age_group`, `limitations`, `profile_updated_at`). PATCH is partial: an omitted key is unchanged, an explicit `""`/`null` clears it. 30/min. This is what the coach reads as trusted context — see "Trusted context" in AI Guardrails.
- `GET /export` (full JSON), `GET /export/sets.csv` (flat per-set CSV) — both 5/min, `Content-Disposition` attachment.
- `DELETE /sessions/{id}/sets/{set_id}` — remove a set from an in-progress session (409 once completed).
- `GET /progress/exercises`, `GET /progress/exercises/{id}`, `GET /progress/records` (per-exercise PRs: top weight, est. 1RM, best set volume)
- `GET/POST /programs`, `GET/PATCH/DELETE /programs/{id}`, `PUT /programs/{id}/days`, `GET /programs/active/next`
- `GET /health` — liveness probe (`{"status":"ok"}`). **Unauthenticated.**
- `GET /version` — running build: `{name, version, commit, built_at}`. **Unauthenticated** (so the app's Settings → About can show it pre-login). `commit`/`built_at` are stamped at deploy time (see Deployment); `"unknown"` otherwise.
- `GET /workouts?date=` — **cross-app**, read-only training status for the sister app **Plate**: `{date, trained, strength_sessions, cardio_sessions}` (counts *completed* strength + cardio sessions). **Not** Spotter's own user-token auth — it takes a cross-app JWT signed with `CROSS_APP_SECRET` carrying the user's email, resolved to a Spotter user by email (`get_cross_app_user`). Disabled (401) unless `CROSS_APP_SECRET` is set. 60/min.

## Security
- Auth on every endpoint (token-based); no anonymous access to user data. The only unauthenticated endpoints are `GET /health` and `GET /version`, which expose no user data.
- **Rate limiting** (slowapi): `/auth/register` 5/min, `/auth/login` 10/min, `/auth/refresh` 10/min, forgot/reset 5/min, `/ai/chat` 20/min, `/ai/programs/accept` 20/min, `/ai/sessions/{id}/adjust` 20/min. Security headers added via middleware.
- LM Studio bound to localhost; only FastAPI reaches it.
- Secrets in env vars / `.env` (gitignored), never committed.
- HTTPS between Android and server: terminate TLS at a reverse proxy for real deployments. (The debug client currently allows cleartext for localhost dev — not for production.)

## Deployment
Self-hosted via Docker Compose (root `docker-compose.yml`: `db`, `server`, and a `cloudflared`
tunnel behind the `tunnel` profile). Migrations run automatically on container boot
(`server/docker-entrypoint.sh` → `alembic upgrade head`). Full operator guide in `deploy/README.md`.

- **Remote redeploy (`deploy/`):** a **self-hosted GitHub Actions runner** on the host
  (`.github/workflows/deploy.yml`, label `spotter`) auto-redeploys after CI passes on `main`
  (`workflow_run`), plus a manual `workflow_dispatch` with a `ref` input that doubles as a
  rollback. The runner long-polls GitHub outbound — no inbound ports. The workflow uses
  `shell: powershell` (Windows PowerShell 5.1 — no PowerShell 7 needed) and drives the canonical
  clone via the `SPOTTER_DIR` Actions variable (no `actions/checkout`).
- **Redeploy logic (one per OS):** `deploy/redeploy.ps1` (Windows/Docker Desktop, primary) and
  `deploy/redeploy.sh` (Linux/macOS) — fetch → `git reset --hard <ref>` → `docker compose up -d
  --build` → health-gate on `/health` → prune. They export `GIT_SHA`/`BUILT_AT` so `GET /version`
  reports the running commit. `.env` (gitignored) and the `pgdata` volume survive `git reset`.
- **Tunnel + deploys:** `cloudflared` is behind the `tunnel` profile, so a plain `docker compose
  up` excludes it (a deploy would drop the tunnel → Cloudflare `530`s). Set `COMPOSE_PROFILES=tunnel`
  in the **root `.env`** so Compose keeps it in the managed set across deploys.
- **LM Studio in Docker:** inside the `server` container `localhost` is the container, so set
  `LM_STUDIO_BASE_URL=http://host.docker.internal:1234/v1` in `server/.env` (else `/ai/chat`
  returns `503`). A `530` (vs `503`) means the tunnel itself is down.
- **Verify a deploy:** app **Settings → About** shows app + server version/commit, or
  `curl http://127.0.0.1:8000/version` (locally) / the public hostname (through the tunnel).

## Testing
- Server: pytest for routers + the AI guardrail/validation layer (mock the LLM; assert malformed/out-of-bounds output is rejected).
- Android: unit-test ViewModels and the sync/repository logic.

## Design system (PULSE) — now the shared library

Spotter consumes the shared **Pulse library** (`design.pulse:pulse-ui`) via a Gradle composite build
(`settings.gradle.kts` → `includeBuild("../../Pulse")`; the sibling `Pulse` repo must sit next to
`Spotter`, and CI/release check it out) — migrated off the in-tree copy 2026-07-03. Spotter **leads
blue** via `PulseAccent.Blue`. The app-side layer is `ui/theme/SpotterTheme.kt` (the workout channel
map `PulseColors` — effort/strength/streak/recovery + structure + hero/energy gradients + `dayChannel`;
`SpotterTheme.pulse`). `ui/theme/AppLocals.kt` (weight/distance units) stays app-side. Generic tokens
+ components come from the library.

**Spotter was the component unification.** Spotter's components were the original, richer PULSE set;
the library (extracted from Plate's leaner rewrite) was brought up to Spotter's versions as supersets:
`StatTile` (dense metric layout via `dense = true` — icon/animatedValue/sparkline), `PanelCard`
(onClick/channel/raised/contentPadding), `SectionHeader` (trailing slot), `Sparkline` (filled-line
mode via `strokeWidth`), and `TickerNumber` were promoted into `design.pulse.ui.components`. The other
apps (Cookbook/Dragonfly/Plate) render pixel-identically against the supersets; Spotter converged on
two trivial cosmetics (section-header tick 12→14dp + rounded, and ~8dp section vertical spacing) — its
Roborazzi baselines were re-recorded. Spotter-specific components stay app-side: `BrandLogo`,
`ConfettiHost` (`CelebrationPulse`), `ExercisePreviewRow`, `HeatBar`, `States`, and `Modifiers`
(`hairline`/`shimmer`). **Do not reintroduce in-tree token/component copies** — fix them in Pulse and
rebuild all four consumers.

## Conventions for Claude
- **Update `ARCHITECTURE.md` in the same PR** when a change alters architecture — a module's
  responsibility, a layer boundary, a cross-app contract, or the data model. Silently-drifting
  docs are how this repo's API docs said `/plans` for a round (ROADMAP2 T2 #5c).
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

## Sprint 3 — Features (2026-06-05)
Delivered (Android: `:app:testDebugUnitTest` + `:app:compileDebugKotlin` green; server prompt
change is text-only, covered by the existing AI guardrail tests):
- **Preset programs (client-side):** new **Preset programs** screen (`ui/program/ProgramPresetsScreen.kt`
  + `ProgramPresetsViewModel.kt`), reachable from the Programs screen (a "Presets" top-bar action
  and a "Browse presets" empty-state button). Six curated starters live in
  `ui/program/ProgramPresets.kt` (StrongLifts 5×5, PPL, Upper/Lower, Full Body, Dumbbell-only,
  Bodyweight), defined by exercise **name**. Applying one fetches `GET /exercises`, resolves
  names → ids (dropping any unresolved), and reuses `POST /ai/programs/accept` to create the
  plans + program and activate it (replacing any prior active program) — **no server change**.
  Presets prescribe structure (movements/sets/reps) plus a conservative starting weight for
  every weighted movement (since 2026-06-10 — weighted lifts previously had no target and
  rendered as "BW"). A catalog guardrail test asserts every preset name matches a seeded
  exercise and that weighted/bodyweight exercises carry/omit a starting weight respectively.
- **Personalized greeting:** the Home greeting now appends the signed-in user's first name
  (e.g. "Good afternoon, Sonic"). `HomeViewModel` fetches the name via `getMe`, takes the first
  whitespace-delimited token, and falls back to the plain time-of-day greeting when offline.
- **Calendar self-sync:** `loadMonth(sync=true)` now pulls programs + plans (and pushes pending
  sessions) on first load and on resume, so opening Calendar without a prior sync no longer shows
  an empty schedule; a "No active program" hint links to the AI coach / Programs when nothing is
  scheduled.
- **[AI guardrail/prompt change] Intake Protocol no longer re-asks known onboarding info.**
  `app/services/ai/prompts.py` now instructs the model to read the `## User Profile` context
  first, treat any item already present (equipment, experience, goal, age range, limitations,
  training days) as answered, ask only for genuine gaps, and skip intake entirely when everything
  is known. Behaviour change is confined to the prompt module; no validation/scope changes.

## Sprint 4 — Remote redeploy + version readout (2026-06-07)
Delivered and **verified end-to-end on the live deployment** (server: 137 pytest green incl. new
`tests/test_version.py`, `ruff check app` clean; Android: `:app:testDebugUnitTest` green incl. new
`SettingsViewModelTest` cases; the self-hosted Deploy ran green on `main` and the public hostname
served the deployed commit through the Cloudflare tunnel). See the **Deployment** section above for
the operational model and `deploy/README.md` for the operator guide.
- **Remote redeploy pipeline:** `.github/workflows/deploy.yml` + `deploy/redeploy.ps1` /
  `deploy/redeploy.sh`, run by a self-hosted runner. Auto-deploys CI-green commits on `main`;
  manual `workflow_dispatch` with a `ref` input for ad-hoc deploys/rollbacks. No inbound ports.
- **Version readout:** server `GET /version` (unauthenticated, mirrors `/health`); `APP_VERSION`
  is a single source reused by the FastAPI app; `git_sha`/`built_at` settings injected at deploy
  time via `docker-compose` (`GIT_SHA`/`BUILT_AT`). Android: `VersionOut` + `ApiService.getServerVersion()`,
  `SettingsViewModel` exposes `appVersion` (BuildConfig) and `serverVersion`, surfaced in a new
  **Settings → About** section (app version + server version·commit + deploy timestamp).
- **Operational notes captured in docs:** `COMPOSE_PROFILES=tunnel` (root `.env`) keeps
  `cloudflared` up across deploys; `LM_STUDIO_BASE_URL=http://host.docker.internal:1234/v1`
  (`server/.env`) lets the container reach LM Studio. `530` = tunnel down, `503` = LM Studio down.
- **Deferred:** installing the runner as a Windows service (currently runs interactively via
  `run.cmd`); the deploy recreates the `server` container each run (env stamp changes), a brief
  blip that's acceptable for personal use.

## Audit Resolution Log (2026-06-10)
A second full-codebase audit (server / Android / infra+tests, candidate findings verified
against the code before fixing — several were dismissed as false positives, see below).
Fixed and verified: server 144 pytest green + `ruff check app` clean; Android
`:app:testDebugUnitTest` + `:app:compileDebugKotlin` green; `docker compose config` validates.

### Server — fixed
- **[MED] Malformed LM Studio response → 500.** `ai/client.chat` parsed `resp.json()` and the
  `choices[0].message.content` path outside its try/except, so a non-OpenAI-shaped 200 raised an
  unhandled exception. Now mapped to **502** ("malformed response"); parametrized regression
  tests in `test_ai_guardrails.py` cover non-JSON / missing choices / missing content / non-string.
- **[MED] Dead legacy plan code removed.** `routers/plans.py`, `services/plan_service.py`,
  `models/workout_plan.py`, `models/planned_exercise.py`, `schemas/plan.py` were unreachable
  since the plan→routine rename (router unmounted, models unimported) — and `workout_plan.py`
  still pointed at the dropped `workout_plans` table with `back_populates` targets that no longer
  exist, so any future import would have broken SQLAlchemy mapper configuration app-wide. Deleted.

### Android — fixed
- **[MED-HIGH] Token refresh hardened** (`TokenRefreshAuthenticator`): refreshes are now
  serialized behind a lock and re-check the stored token first (N concurrent 401s → one refresh);
  a transient `IOException` mid-refresh now fails the request **without** signing out (previously
  any network blip during refresh cleared tokens and bounced the user to login); only a 401/403
  from `/auth/refresh` signs out — a 5xx no longer wipes the session.
- **[MED] `RoutineDetailViewModel.saveEdits` no longer swallows errors:** failure surfaces via an
  `error` StateFlow → snackbar in `RoutineDetailScreen`, and the user stays in edit mode with the
  draft intact. Regression test added.
- **[LOW] Offline-added sets render fully:** `SessionRepository.logSet` now copies display
  enrichment (exercise name, targets, superset group) from a sibling set of the same exercise,
  so a "+" set added offline isn't a nameless/ungrouped row until next sync.
- **[LOW] Workout timer reuse:** `WorkoutViewModel` resets `elapsed` when loading a *different*
  session (tracked via `timerSessionId`) — rotation/resume of the same workout keeps the timer.
- **[Tests] Calendar self-sync covered:** `CalendarViewModelTest` now asserts the init load syncs
  programs/routines/pending sessions, month paging doesn't re-sync, and sync failure still loads.

### Infra — fixed
- **[MED] cloudflared healthcheck added** (`--metrics 0.0.0.0:2000` + `cloudflared tunnel ready`):
  a tunnel with no live edge connections (bad/missing `TUNNEL_TOKEN`) now shows `unhealthy`
  instead of a silent green deploy; the redeploy scripts print the tunnel health after the
  `/health` gate (informational, not gating).
- **[LOW-MED] Postgres credentials parameterized** in `docker-compose.yml`
  (`${POSTGRES_USER:-spotter}` etc.); the `db` service and the server `DATABASE_URL` share the
  same variables so they can't drift. Documented in `deploy/README.md`.
- **[LOW] `--remove-orphans`** added to `docker compose up` in both redeploy scripts.

### Dismissed as false positives (verified — not bugs)
- "`logSet` only marks `syncPending` when offline" — it's set unconditionally on insert; the
  offline path is picked up by `getUnsyncedNewLogs` in sync step 2.
- "`ProgramRepository.updateProgram` deactivates locally before the API call" — API call is first.
- "`CalendarViewModel` shows stale projections on error" — `_projected` is cleared synchronously
  at the top of `loadMonth`.
- "entrypoint ignores migration failure" — `set -euo pipefail` aborts on alembic failure.
- "manual `workflow_dispatch` deploys bypass CI" — deliberate, documented rollback lever.
- "missing `user_id` on post-mutation re-fetch" — re-fetch is by UUID PK after the ownership
  check in the same request; not exploitable.
- "`SetLogCreate` should carry `superset_group`" — the server derives it from the routine at
  read time; it isn't stored per set. (Local display gap fixed via sibling enrichment above.)

### Newly deferred (in addition to the existing backlog)
- **[LOW][Android] Offline-finished workouts show no muscle-group breakdown** — the summary's
  `muscle_groups` is server-computed and exercises' muscle groups aren't cached locally; a local
  computation needs `muscle_group` added to the routine payload + a Room column. Do alongside the
  broader offline-writes design. **(FIXED 2026-07-17** — via an exercise-catalog Room mirror +
  `OfflineMuscleGroups`, not the routine-payload approach; see ARCHITECTURE.md "Offline model".)
- **[LOW][Infra] Redeploy failure log capture** (dump `docker compose logs` on health-gate
  failure) and a configurable health timeout — nice-to-haves for operability.

### Follow-up fix (same day): weighted lifts no longer start as "BW"
Weighted exercises created via presets or AI programs could land with `target_weight = null`
and render as bodyweight (e.g. Bench Press shown "5×5 BW" — a bar alone is 45 lb).
- **Presets:** `PresetExercise` gained a `weight` field; all six presets now prescribe
  conservative plate-friendly starting loads (e.g. squat/bench/row 95, OHP 65, deadlift 135,
  dumbbells per-hand). New guardrail test asserts weighted ⇒ weight set, bodyweight ⇒ null.
- **[AI prompt change]** `prompts.py` plan/program rules now make `target_weight` REQUIRED for
  weighted exercises (null only when `is_bodyweight`), estimated from training history when
  known, and never below the 45 lb bar for barbell movements. Prompt-module-only change; the
  extraction/clamp layer is untouched.

### Follow-up feature (same day): special-case preset programs
Four new client-side presets in `ui/program/ProgramPresets.kt` for training around a
constraint, using only seeded exercises (guardrail test passes unchanged):
- **Knee-Friendly Strength** (3 days) — upper push/pull + a knee-sparing hips/hamstrings day
  (bridges, hip thrust, seated leg curl, RDL); no squats, lunges, or leg extensions.
- **Prenatal — Third Trimester** (2 days) — seated/standing only, nothing supine, light loads,
  no core flexion.
- **Postpartum Rebuild** (2 days) — bodyweight foundations then light strength; no crunches,
  no heavy lifting.
- **Lower-Back Friendly** (2 days) — machines, supported rows, and glute work instead of
  heavy hinging off the floor.
Each description embeds a get-cleared-by-your-doctor/physio line; these are exercise-selection
presets, not medical advice, in keeping with the app's non-medical scope.

## Sprint 5 — "PULSE" UI redesign (2026-06-11)
A full visual redesign of the Android app to a data-forward, instrument-panel design system
("PULSE"). All 20 screens restyled; server untouched.

### Design system (`ui/theme/`)
- **Channel colors** (`Pulse.kt` → `PulseColors`, via `SpotterTheme.pulse`): each data domain owns
  a hue in the original brand family — **effort electric blue** `#4D7CFF` (volume/work/timers/
  primary actions), **strength violet** `#8B7CFF` (PRs/loads), **streak orange** `#FF8A5C`,
  **recovery green** `#34D399` (rest/done). Each channel has base/dim/on values; light theme uses
  contrast-safe `*Deep` variants. Two brand gradients live in the same layer: `heroGradient`
  (blue→indigo — Home greeting, default `PulseButton`) and `energyGradient` (orange→amber —
  celebration CTAs like the summary's Return to Home). Structural tokens: `panel`/`panelHigh`
  surfaces + 1px `hairline`/`hairlineStrong` strokes — depth on cards comes from stroke + tone,
  not shadows. Dark-first OLED (`#0B0D10` bg); the Settings System/Light/Dark toggle is unchanged.
- **Type**: Space Grotesk (display/headline/title), Inter (body/label; labels run uppercase with
  wide tracking as instrument captions), **JetBrains Mono for every numeral** via a dedicated data
  scale (`DataType.kt` → `SpotterTheme.dataType`: numeral 14 → dataXL 60, slashed zeros). UI scale
  is a minor third (12/14/17/20/24/29). Sora was removed. **Fonts ship as STATIC per-weight
  instances** (generated with fonttools `varLib.instancer`) — variable fonts' `FontVariation`
  weight axis is ignored on some devices and renders the lightest master (real-device bug found
  on first install), so never reintroduce variable-font weights.
- **Motion tokens** (`Motion.kt` → `PulseMotion`): Fast 120 / Standard 240 / Emphasized 400 /
  Data 600ms with shared easings + press spring. **Shapes** tightened to 8/12/16dp.
- **Components** (`ui/components/`): `PanelCard` (hairline-stroked flat surface, optional channel
  tint), `PulseButton` (solid channel block; `tonal`/`compact` variants), `DataText`/`TickerNumber`
  (mono readouts; rolling count-up), `ProgressRing`, `Sparkline`, `HeatBar`, `CelebrationPulse`,
  restyled `StatTile` (channel + sparkline) and `SectionHeader` (channel tick + uppercase label).
  `GradientButton`/`SpotterCard`/`AnimatedCounter`/`BrandColors` were deleted — no gradients,
  no emoji, confetti recolored to channels and toned down (40 particles).

### Navigation shell
- **Bottom navigation** (`ui/navigation/PulseBottomBar.kt` + `TopLevelDestination.kt`):
  Home · Calendar · Coach · Progress, wrapped around the existing NavHost in `AppNavGraph.kt`
  (one Scaffold; `consumeWindowInsets` prevents double insets; tab nav uses
  saveState/restoreState). Routes/`Screen.kt` unchanged.
- **Workout resume strip** (`WorkoutResumeBar`): shown above the bottom bar anywhere in the app
  while a session is in progress. Driven by `util/ActiveWorkoutStore.kt` — observes Room for an
  `in_progress` session dated today, so it survives process death and self-clears.
- **Re-homed actions**: Home top bar = Add routine + Settings only (overflow menu and both FABs
  removed); bodyweight logging = tap the Home bodyweight stat tile; Programs link = "Upcoming"
  section header; History = Progress top-bar icon + Settings; Exercise Library = Settings
  ("Library & data" group). Calendar/Coach/Progress lost their back arrows as tabs (Coach keeps
  one only when opened session-aware from a workout).
- **Action grammar**: cards navigate, explicit buttons act, menus live on their own icons.

### Screen highlights
- **Workout**: rest timer is a full-width instrument panel — 150dp recovery-green `ProgressRing`
  with 44sp mono countdown while resting, slim cyan count-up strip while working
  (`WorkoutViewModel` gained additive `restDurationSeconds` for real ring progress); set rows use
  centered mono `BasicTextField`s on raised panels; completed sets wash recovery green.
- **Summary**: total volume is the 60sp mono cyan centerpiece; PR pill in violet; per-muscle
  volume as `HeatBar`s; recovery ring-check with `CelebrationPulse`.
- **Home**: flat greeting panel + one-line "next up" status; channel stat tiles (streak amber,
  active-minutes cyan with a Mon–Sun sparkline via additive `HomeViewModel.weeklyMinutesByDay`).
- **Progress**: per-tab channel (bodyweight cyan, strength/records violet); `LineChart` redrawn —
  2dp line, hairline gridlines, glow dot on the latest point only.
- **Calendar**: day numerals in mono; status = green dot (done), blue dot (in progress); planned
  workouts get a solid dot in their program day's channel via `PulseColors.dayChannel(index)` —
  day 1 orange, day 2 blue, day 3 violet, day 4 green, repeating (`UpcomingWorkout.dayIndex`
  threaded from `WorkoutProjection`); rest days keep a quiet ring.
- **Home**: the "Your routines" list was replaced by **"Your programs"** (program cards with day
  count + ACTIVE badge → `ProgramDetail`; "Manage" → Programs screen). Routines remain editable
  through program days and the top-bar "+".

### Verification
Android: `:app:compileDebugKotlin` + `:app:testDebugUnitTest` green; Roborazzi baselines
re-recorded (`app/screenshots/`, dark + light per scene incl. a bottom-bar/resume-strip shell
scene). No server or schema changes; no new dependencies (fonts are bundled assets, OFL).

## Sprint 6 — AI live-workout adjustments (2026-06-13)
The session-aware coach can now change the live workout, not just advise. Mid-session the user
says e.g. "I can't do bench press"; the AI proposes a structured adjustment that surfaces as an
**Apply card** (with a "future workouts too" toggle, default ON). Same trust model as Save
Program — the AI emits a *suggestion*; nothing persists until the user taps Apply. The AI gains
no autonomous write path. (Server: 167 → 190 pytest green + ruff clean; Android:
`:app:testDebugUnitTest` + `assembleDebug` green; baselines re-recorded incl. a coach-adjustment
scene.)

- **Actions (v1):** swap exercise, adjust weight, remove, add/re-add — "put bench back in" is just
  an `add`. The card lists one `summary` line per action.
- **Server (guardrail-isolated):** `prompts.py` gained a Live Workout Adjustments section (gated on
  the live-session context block; only-incomplete-sets rule; acute-pain redirect; library names;
  6-action cap). `client._extract_adjustment` only runs when `current_session_id` is present;
  precedence is adjustment > program > plan (exactly one suggestion per reply). New
  `services/ai/adjustment_apply.py` + `POST /ai/sessions/{id}/adjust` (20/min) validates
  ownership/in-progress/exercise-existence and mutates **only incomplete sets** in a single
  transaction; `apply_to_routine` rewrites the routine's `RoutineExercise` rows (program days pick
  it up — no program writes). `MAX_ADJUSTMENT_ACTIONS` lives in `app/limits.py`.
- **`session_service.get_session` enrichment:** exercises in the session but not the routine
  (swaps, ad-hoc adds) now get name + muscle-group fallbacks instead of rendering nameless /
  dropping out of the muscle-group summary.
- **Android:** `AiModels` gained `SuggestedAdjustment(Action)` + `ApplyAdjustmentRequest` and
  `ChatResponse.suggestedAdjustment`; `SessionRepository.applyAdjustment` does a **wholesale Room
  reconciliation** (the incremental `getSession` merge never deletes server-deleted rows, which
  swap/remove produce) — preserve unsynced-local rows, `deleteBySession`, re-insert from the
  response. `AiChatViewModel` surfaces `pendingAdjustment` + `applyAdjustment(applyToRoutine)`
  (syncs routines when true; keeps the card on failure). `AiChatScreen` adds `SuggestedAdjustmentCard`.
- **Workout freshness:** `WorkoutScreen` now reloads on `ON_RESUME` (returning from chat doesn't
  re-key a `LaunchedEffect(sessionId)`), and `WorkoutViewModel.loadSession` only shows the spinner
  when nothing is on screen yet, so an applied adjustment appears without a flash.
- **Deferred:** no undo stack — reverting is conversational ("put bench back in" = another `add`).
  Editing a *completed* session is still disallowed (409). Offline apply is unsupported by design
  (chat requires the server anyway).

## Sprint 7 — Cardio module (2026-06-14)
A net-new **Cardio** feature area alongside strength training, with its own interval-timer run
screen (distinct from the set/rep lifting UI). Two programs ship: **Couch to 5K** (guided 8-week ×
3-day) and **Free Run** (open-ended or custom intervals). Verified: server 175 pytest green +
`ruff check app` clean (8 new `tests/test_cardio.py`); Android `:app:testDebugUnitTest` +
`:app:assembleDebug` green (new `CardioProgramsTest`, 6/6). No new dependencies.

- **Integration path (chosen):** server-backed `CardioSession` + Room mirror, matching the existing
  convention that sessions are the server's source of truth (not the spec's Room-only fallback).
  Program *definitions* are static client-side (`ui/cardio/CardioPrograms.kt`); only session records
  persist. The AI post-run coaching hook is **deferred** (sessions are stored so it can be added).
- **Server (cardio-isolated):** `models/cardio_session.py`, `schemas/cardio.py`,
  `services/cardio_service.py`, `routers/cardio.py` (`GET/POST /cardio/sessions`,
  `PATCH /cardio/sessions/{id}`), migration `0010`. Auth + per-user ownership like every other
  endpoint; status restricted to `in_progress|completed|abandoned`; `completed` stamps `completed_at`.
  No `GET /cardio/programs` — definitions are static client-side.
- **Android:** `CardioSessionEntity`/`Dao` (Room v5→v6, `MIGRATION_5_6`), `CardioRepository`
  (offline-tolerant local-first writes + best-effort server push, dedup-safe sync), the static
  catalog + an 8-week C25K table (5-min warm-up + run/walk + 5-min cool-down each day, ramping to a
  continuous 30-min run; every day's intervals sum to its total — asserted in tests). Screens:
  `CardioHomeScreen`, `CardioOverviewScreen` (completed/current/upcoming day states, Resume/Restart,
  segmented `IntervalBar` preview, 3×/week target dates), `CardioRunScreen` (phase label +
  recovery-green `ProgressRing` countdown, lock toggle, Pause/Skip-warm-up/Finish), `FreeRunConfigScreen`.
- **Drift-free timer:** `CardioRunController` (@Singleton) measures time from
  `SystemClock.elapsedRealtime` deltas (not a tick counter), so pause/resume and screen-off never
  accumulate error; phase transitions cue via vibration + optional TTS. `CardioRunService` is a
  foreground service (`specialUse` type — no extra runtime permission) that keeps the run alive
  backgrounded and self-stops when the run ends.
- **Theme:** all colors/type/shapes come from PULSE tokens — cardio uses the **recovery green**
  channel for active/run, **streak amber** for completed (trophy), reusing `PanelCard`, `PulseButton`,
  `ProgressRing`, `DataText`. No new design language.
- **Deviations from the spec (to match existing Spotter conventions):** (1) integrated server path
  instead of Room-only; (2) the "green primary accent" maps to the existing `recovery` channel
  rather than a hardcoded green; (3) Cardio is added as a 5th bottom-nav tab (the app uses a bottom
  bar, not a drawer); (4) added a **Finish** action (from the paused state) so Free Run and early
  exits can complete — the spec only named Pause/Skip.
- **Deferred:** AI post-run coaching note; GPS/distance/pace; audio/music. The emulator path needs a
  KVM host, so the run screen was verified by build + unit tests, not interactive UI.

## Sprint 8 — Unified background timers (2026-06-15)
The workout, rest, and cardio timers were unified onto one drift-free, background-correct model so
every timer stays accurate when the phone is locked, the app is backgrounded, or the process is
killed (Android: `:app:testDebugUnitTest` + `:app:assembleDebug` green; no server/schema changes,
no new deps). Cardio was already the gold standard (`CardioRunController` @Singleton, monotonic
`elapsedRealtime` deltas, foreground service + wake-lock); this brings the workout side up to it.
- **Root problem fixed:** the workout session elapsed clock and the on-screen rest ring were naive
  `delay(1000)` counters in `WorkoutViewModel` that drifted, **froze when backgrounded**, and reset
  on process death — so the in-app clock disagreed with its own notification chronometer and the
  server-reported `durationSeconds` was wrong after any backgrounding.
- **New `util/TimeProvider.kt`** (injectable `nowMs`/`elapsedRealtimeMs`, `@Binds` to `SystemTimeProvider`)
  — one clock seam everywhere, deterministically faked in tests.
- **Elapsed clock** is now recomputed each tick from the persisted `WorkoutSessionEntity.startedAtMs`
  epoch anchor (`SessionRepository.getStartedAtMs`), matching the notification chronometer and bottom
  bar; `durationSeconds` on finish is anchor-derived (the core bug fix).
- **New `ui/workout/WorkoutTimerController.kt`** (@Singleton, the workout analogue of
  `CardioRunController`): single source of truth for the rest countdown + work count-up. Drift-free
  (recomputes from a monotonic end-anchor), runs in an app-scoped coroutine (`@ApplicationScope`,
  added to `DispatchersModule`) so the end-of-rest **vibration fires once even backgrounded**, owns
  the rest `PARTIAL_WAKE_LOCK` itself (acquired synchronously in `startRest`, no CPU-sleep race), and
  uses a **generation guard** so a superseded/skip-raced countdown can't clobber newer state, leak the
  wake-lock, or fire a stray cue. `WorkoutViewModel` and the service only *read* its `restState`, so
  the ring and notification stay in lock-step (no parallel counter).
- **One notification per workout:** `RestTimerService` was **deleted** and its rest countdown merged
  into `WorkoutSessionService` (elapsed chronometer + `"Resting · M:SS"`/`"x/y sets"` line, combined
  from `ActiveWorkoutStore` + `restState`; rest state changes at most once/sec so no notify spam).
- **Shared infra `util/ForegroundServiceSupport.kt`** (`startForegroundSpecialUse`, `ensureChannel`,
  `tapIntent`, null-tolerant `WakeLockHolder`) — adopted by the merged workout service **and**
  `CardioRunService`, removing the triplicated channel/wake-lock/foreground/deep-link boilerplate.
  Cardio's proven timing internals were intentionally left untouched (behavior-preserving).
- **Tests:** `WorkoutViewModelTest` rewired to the new constructor + a scheduler-backed fake clock
  (elapsed reflects the anchor; finish sends anchor-derived duration; rest/work timers deterministic);
  new `WorkoutTimerControllerTest` pins the drift-free `remainingSec` math.
- **Deferred:** rest state is not persisted across process death (the countdown vanishes on relaunch,
  elapsed stays correct) — matches prior behavior; offline has no bearing (timers are local).

## Sprint 9 — Cardio programs in Upcoming (2026-06-15)
Accepting a guided cardio program (e.g. Couch to 5K) now schedules it: its next runs appear in the
Home "Upcoming" block and on the Calendar alongside any strength program, so a user can run a
strength **and** a cardio program at once. Android-only (no server/schema change); verified
`:app:testDebugUnitTest` (incl. new `CardioScheduleTest`, updated Home/Calendar VM tests) +
`:app:assembleDebug` green.
- **Active cardio program (client-side):** cardio definitions are static client-side, so — unlike
  strength `WorkoutProgram.is_active` — there is no server flag. A new `AppPreferences`
  `activeCardioProgramId` (DataStore) is the source of truth for "do upcoming runs show?". The
  Cardio overview gained an **Add to / Remove from schedule** card (`CardioOverviewViewModel`
  `isOnSchedule`/`setOnSchedule`); only one cardio program is active at a time.
- **Shared scheduling (`ui/cardio/CardioSchedule.kt`):** the completion-driven "next run + target
  date" math (3-per-week 2/2/3 cadence, overdue clamped to today) was extracted from
  `CardioOverviewViewModel` into one helper so the overview, Home, and Calendar all agree.
  `CardioSchedule.upcoming(...)` returns cardio runs as `UpcomingWorkout`s carrying a new
  `CardioUpcoming` payload (so a slot is a run when `cardio != null`; `routineId` stays null).
- **Home / Calendar:** `HomeViewModel.loadUpcoming` and `CalendarViewModel.computeProjected` now
  project strength **and** cardio independently and merge by date (Home keeps the 4 soonest). The
  strength-only early-returns were removed so cardio-only users still see a schedule.
  `UpcomingWorkout` cards / calendar dots/detail render cardio in the **recovery green** channel and
  tap through to the Cardio overview (no inline "Start"). The Calendar projection map became
  `Map<LocalDate, List<UpcomingWorkout>>` so a strength day and a cardio run can share a date.
- **Deferred:** Free Run is open-ended (no schedule), so it is not acceptable/scheduled; only guided
  programs surface in Upcoming. No direct "start the run" from Home/Calendar — the card opens the
  overview where the day is started (keeps the run-launch path in one place).

## Sprint 10 — Cross-app workout status for Plate (2026-06-16)
Added a small **read-only `GET /workouts?date=`** endpoint so the sister app **Plate** (calorie/macro
tracker) can apply a training-day intake bump when the user trained. This is the server side of
Plate's Phase 7 "Spotter-awareness"; verified server **185 pytest green** (10 new
`tests/test_workouts.py`) + `ruff check app` clean. Additive — **no schema change**, no change to
Spotter's own clients.
- **Endpoint (cross-app-isolated):** `routers/workouts.py` + `services/workout_service.py` +
  `schemas/workout.py`. Counts *completed* `WorkoutSession`s (by `date`) and `CardioSession`s (by the
  day's UTC `completed_at` window) and returns `{date, trained, strength_sessions, cardio_sessions}`.
- **Auth (deliberately separate surface):** `get_cross_app_user` (new in `security.py`) accepts a JWT
  signed with the new **`CROSS_APP_SECRET`** setting and typed `cross_app`, carrying the user's
  **email** — the only stable identity across the two apps' independent `users` tables — and resolves
  the Spotter user by email. It does **not** trust Spotter's own `secret_key`/`access` tokens, and a
  cross-app token can't act as a normal Spotter session. Unset secret ⇒ the endpoint 401s (disabled).
  Rate-limited 60/min. The shared-DB option was considered and rejected (would couple Plate to
  Spotter's schema + co-locate the databases); the endpoint gives a stable contract instead.
- **Integration shape** was a gated decision (Plate CLAUDE.md §8) confirmed before building: read-only
  endpoint over HTTP, not a shared backend.

## Suite membership — Dragonfly hub, SSO, and release automation (2026-07-02/03)

Spotter is one of five apps in the personal suite (Spotter, Plate, Cookbook, Hawksnest,
Dragonfly). The suite-wide architecture and its as-built ledger live in the **Dragonfly repo**
(`CLAUDE.md` + `BROKER.md`) — read those before touching anything in this section. What Spotter
carries:

- **Suite signing key (Phase 0).** Release APKs are signed with the shared suite key (kept
  outside all repos on the host; the repos are public). `release.yml` builds a signed APK +
  `version.json` on any push to `main` touching `android/**`, and a post-build `apksigner` guard
  fails the release if the signer cert ≠ the pinned suite SHA-256 (`5a596c9e…`). versionCode =
  epoch minutes — so a local debug build can never install over a CI release without
  uninstalling first.
- **Config broker (Phase 1).** `util/SuiteConfigReader` queries Dragonfly's
  signature-permission ContentProvider (`content://com.dragonfly.suiteconfig/config/spotter`)
  in `App.onCreate` and applies a hub-managed server URL to `AppPreferences.serverUrl`; silently
  falls back when the hub is absent/denied/blank. Manifest carries the `<uses-permission>` +
  `<queries>` entries for `com.dragonfly`.
- **SSO (Phases 2b/2c — LIVE).** Server: `POST /auth/suite` (`routers/suite_auth.py` +
  `services/suite_auth.py`) validates an RS256 suite access token against the identity server's
  JWKS (https://id.dragonflymedia.org; `aud=suite`, issuer-checked; JWKS fetch uses a hardcoded
  8 s timeout by design) and find-or-creates the local user **by email** (unusable random
  password hash). Feature-flagged: without `SUITE_JWKS_URL`/`SUITE_ISSUER` it 404s and password
  auth is untouched. **Those two vars are pinned in `docker-compose.yml`'s `environment:` block
  on purpose** — Compose doesn't re-read changed `env_file` content on redeploy, and losing the
  flag that way caused two production 404 regressions. Client: AppAuth
  (`data/remote/SuiteAuthManager.kt`, client id `spotter`, redirect `com.spotter:/oauth2redirect`)
  behind the "Sign in with Dragonfly" button; email/password login remains as fallback. The
  manifest overrides `net.openid.appauth.RedirectUriReceiverActivity` with an AppCompat theme
  (`tools:node="merge"`) — removing that crashes the app on the OAuth redirect because the app
  theme is `android:Theme.Material`, not AppCompat.

### Operational notes specific to Spotter

- **The deploy runner is a Windows service** as of 2026-07-03
  (`actions.runner.CDRaab01-Spotter.DRAGONFLY`, runs as `NETWORK SERVICE`, delayed auto-start),
  like Plate/Cookbook/Dragonfly/kidbot — it self-recovers across reboot. It was originally the
  interactive `C:\actions-runner` (agent name "DRAGONFLY"); converted via
  `C:\Scripts\Convert-SpotterRunnerToService.ps1`. Manual start if ever needed:
  `Start-Service actions.runner.CDRaab01-Spotter.DRAGONFLY`. Because it now runs as NETWORK SERVICE,
  the `C:\Code\Spotter` clone is excepted in the system gitconfig `safe.directory` so deploys don't
  hit "dubious ownership" (host `OPERATIONS.md` §5).
- **`server/.env` history:** the live file was accidentally truncated and reconstructed on
  2026-07-02 with a NEW `SECRET_KEY` (all prior sessions invalidated; users re-login once).
  Custom SMTP / LM-model settings from before that date may be missing rather than "never set".
  `CROSS_APP_SECRET` is the one shared suite-wide value — rotate it in Plate/Cookbook too or
  cross-app calls 401. **2026-07-04:** the live `.env` (and the whole `server`+`cloudflared` stack)
  was still running from the pre-relocation `C:\Users\Sonic\Documents\Code\Spotter`, and that copy
  was **missing `CROSS_APP_SECRET`** (so `/workouts` was silently 401). Consolidated onto
  `C:\Code\Spotter`: rebuilt `.env` = the live values **+** `CROSS_APP_SECRET`, redeployed, and
  repointed `vars.SPOTTER_DIR` → `C:\Code\Spotter`. See host `OPERATIONS.md` §6 (2026-07-04).
- **Local pytest recipe** (the suite is fast when done right, pathological otherwise): use a
  throwaway Postgres, `DATABASE_URL` host **127.0.0.1** (never `localhost` — Docker publishes
  IPv4-only and the ::1-first stall is catastrophic), and `DB_NULLPOOL=1` if you hit "Task
  attached to a different loop" (SQLAlchemy pools bind asyncpg connections to the creating
  event loop). Example: create a scratch DB in the spotter-db container (127.0.0.1:5432,
  spotter/spotter), point `DATABASE_URL` at it, run pytest from `server/`.

## Sprint 11 — Offline writes, 1.0 polish, and home surfaces (2026-07-14 → 16)
The push that closed out Spotter's "Road to 1.0" feature gaps. Server picked up migration `0011`
(body measurements) + `0012` (cardio manual entry) — Alembic is now **12 revisions**; the pytest
suite is **27 files / ~220 tests**. Android verified per-commit by `:app:testDebugUnitTest` +
`:app:compileDebugKotlin`/`assembleDebug` (the emulator path needs a KVM host, so UI was verified
by build + unit tests, not interactive drive — the on-device pass is the last 1.0 item).

- **Offline writes beyond workout mode (retires the standing architectural gap).** Bodyweight
  metrics, routines, and programs are now offline-editable via a **write-through + drain-queue**
  pattern (write to Room with `syncPending`, `NetworkSyncObserver` drains on reconnect); the sync
  step **translates offline-created routine ids → server ids** on push so program-day references
  reconcile without duplicating. Calendar **serves its last-known projection** on an offline read
  instead of throwing. New coverage: offline bodyweight/routine/program queue-and-drain tests.
  (The then-remaining gap — an offline-finished workout showing no muscle-group breakdown —
  was closed 2026-07-17 by the exercise-catalog mirror round; see the section at the end of this
  file.)
- **Rest countdown survives process death.** `WorkoutTimerController` persists the wall-clock rest
  end-anchor via `RestTimerStore` (DataStore) and restores it on init (rest-restore runs *after*
  the wake-lock is initialized — `07c7a1b`), so reopening mid-rest resumes exactly; a rest that
  elapsed while the app was gone is cleared (its cue moment passed).
- **Body measurements beyond weight** (neck/chest/waist/hips/arm/thigh) — server migration `0011`
  + offline write-through; a log-dialog expander logs them alongside the weigh-in, with a
  Measurements trend panel in the Body Weight tab.
- **Est-1RM trend done right** — `GET /progress/exercises/{id}` returns `est_1rm` = the best
  per-set Epley of each day (not independent `max(weight)`/`max(reps)`); the Strength tab gained a
  Weight / Est. 1RM chart toggle. (Progression-engine PR-celebration polish shipped in the same
  round.)
- **Home-screen Glance widget** (`widget/`, `SpotterWidgetReceiver`) — today's workout / set
  progress off a local `WidgetSnapshotStore` snapshot (updated by `WidgetUpdater`), so it renders
  without a network round-trip. Test: `WidgetContentTest`.
- **Static launcher shortcuts** (`res/xml/shortcuts.xml`: Start workout / Log weight / Coach) —
  each fires a `spotter://shortcut/<target>` VIEW intent parked on a `ShortcutBus`; because the
  app gates on auth before the main graph, a shortcut is honoured *after* sign-in, not dropped
  (`util/ShortcutNav.kt` + `ui/navigation/ShortcutViewModel.kt`).
- **Workout-morning nudge** (Tier W2b) — an **opt-in local** reminder (`util/nudge/`,
  WorkManager `WorkoutNudgeWorker`), *not* via the suite push pipeline; re-checks
  enabled/permission/quiet-hours/is-today-a-workout-day at fire time so a stale schedule can't
  nag. Tests: `WorkoutNudgeTest`, `WorkoutNudgeSchedulerTest`.
- **Supersets in the UI** — `WorkoutScreen` renders grouped exercises under a "SUPERSET A/B"
  header driven by the routine's existing `supersetGroup` (shared rest); the server derives the
  grouping, the client only displays it (no schema change).
- **Rest-day fix** — the program projection + `get_next_day` now **auto-skip / auto-consume rest
  days** (`program_service.py`), so a program no longer gets "stuck on a rest day"; the next-day
  suggestion is the next actual workout, and rest days are consumed as their dates pass.
- **Manual cardio entry** — log a walk/run after the fact (activity type + duration + optional
  distance + date): a *completed* session that counts toward the Home streak + active-minutes like
  a guided run. Server: `POST /cardio/sessions/manual` (201) + `activity_type` (`walk`|`run`) /
  `distance_meters` columns (migration `0012`, both nullable — null on existing guided/free runs);
  `program_id="manual"` sentinel. Client: `ManualCardioScreen` (`CardioRepository.logManualSession`),
  distance entered in the user's unit and converted to canonical meters at the edge; completed
  cardio (manual/guided/free) now counts alongside completed strength in `HomeViewModel.loadStats`.

## Offline gaps round — exercise mirror, offline summary, stale banners (2026-07-17)
Closes the offline gaps left open after Sprint 11 (Android-only; no server change). Verified by
unit tests; build/emulator passes are owned by CI.
- **Exercise-catalog Room mirror** (`ExerciseEntity`/`ExerciseDao`, Room v12→13 `MIGRATION_12_13`,
  purely additive): `ExerciseRepository` is now mirror-backed — online reads seed Room as a side
  effect, plus an opportunistic full-catalog refresh in the Home sync round and the reconnect
  observer (`NetworkSyncObserver`). Offline: Exercise Library search (LIKE on name) and preset
  name→id resolution (`ProgramPresetsViewModel` now uses `listAll()`) work from the mirror.
  Degrade rule everywhere: **IOException → mirror; `retrofit2.HttpException` → keep erroring.**
- **Offline muscle-group breakdown** (retires the 2026-06-10 deferred item): offline session
  reads/finishes compute `muscle_groups` locally (`data/repository/OfflineMuscleGroups.kt`, pure +
  table-tested) mirroring the server's semantics — completed sets only, **kg** volume
  (`reps × lb × 0.453592`; null/zero weight = sets but no volume), one decimal, alphabetical
  group order. Exercises missing from the mirror degrade out silently (old empty state).
- **Stale banners on Home + History**: `AppPreferences.lastSuccessfulSyncMs` (stamped whenever a
  sync round reaches the server) feeds Pulse's `StaleBanner` (streak channel) — shown when Home's
  sync probe (the routine pull) hits an IOException, or History's list came from the Room mirror
  (`SessionRepository.listSessionsWithFreshness().fromCache`). HTTP errors never show the banner —
  they keep erroring through the normal paths.
- Accepted offline gap: `getPriorBests` stays empty offline (progression hints are
  server-computed). Tests: new `ExerciseRepositoryTest`, `OfflineMuscleGroupsTest`,
  `SessionRepositoryTest`; updated Home/History/Presets VM tests.

## Audit Resolution Log — Tier 1 (2026-07-28)

A third full-app audit ran 2026-07-28 (`AUDIT-2026-07.md` — AI coach, program structure,
layout/flow, premium roadmap). This round fixed its Tier 1 ("make what exists land"): server
**234 pytest green** + `ruff check app` clean; Android `:app:testDebugUnitTest` green (new
regression tests noted below). On-device pass still owed, as usual.

### Fixed
- **[HIGH][Android] Routine edit wiped supersets.** `RoutineDetailViewModel.startEdit`/`saveEdits`
  dropped `supersetGroup` (create path had it right), so any edit+save destroyed all grouping.
  Regression test: "edit round-trip preserves superset groups".
- **[HIGH][Server][AI guardrail change] Chat-history poisoning.** One blocked phrase anywhere in
  the transcript 422'd every later request forever (client resends full history; guard validated
  every turn fatally). Now: the NEW turn still hard-fails 422; blocked *historical* turns are
  silently dropped from what reaches the model. Injection still never reaches the LLM —
  `test_injection_in_earlier_turn_dropped_not_fatal` pins both properties.
- **[HIGH][Android] Progression suggestions are now appliable.** The chip gained an **Apply**
  button: incomplete sets take the suggested weight/reps and the routine's `target_weight`
  advances (write-back via a client-built `adjust_weight` action on the existing
  `POST /ai/sessions/{id}/adjust` rails). Presets finally progress instead of pre-filling the
  starting weight forever. Tests: 4 new `WorkoutViewModelTest` cases.
- **[MED][Android] Home blank state + routine dead end.** Home now renders a "Your routines"
  section for routines not linked to any program day (tap → RoutineDetail — previously a saved
  routine had NO screen that could open it and the programs-empty+routines-non-empty state
  rendered nothing). Empty state gained "Browse preset programs" (the no-LLM path); Settings'
  empty Programs section gained a nav row; first-run auto-generate failure now snackbars a
  pointer at presets instead of dying silently.
- **[MED][Android] Reset Account now actually re-onboards.** It routed to login, but login
  unconditionally sets `onboardingDone` (deliberate, for returning users on fresh installs) —
  the questionnaire never showed and a program was auto-generated from an empty profile. Reset
  now clears data and navigates straight into Onboarding while still signed in.
- **[MED][Android] Swallowed errors surfaced.** Home `startSession`, Workout `finishSession` /
  `deleteSession`, Calendar `startProjectedSession`, and both `logBodyweight` paths now snackbar
  (finish failure also resets `finishState` so the button recovers); Home's full-screen error
  and the new screens wire `ErrorState(onRetry)`; Progress→Strength handles its error state
  (retry chip) and no longer stacks two full-size empty states.
- **[MED][Android] Completed-session detail.** History cards now navigate: in-progress →
  Workout (the old `onTap` was dead code — resume-from-history never fired), completed → new
  `SessionDetailScreen` (per-set reps/weights, notes, muscle groups; offline-capable). The
  tap-to-expand preview was removed; delete is an explicit icon.
- **[LOW][Android] Cardio parity, first slice.** Run completion is a real summary (stats +
  confetti); Cardio home lists "Recent activity" (`CardioHomeViewModel`) so manual/guided/free
  sessions are visible after the fact. Durations past an hour render `1:15:00` (was `75:00`) in
  Workout + Summary.

### Deliberately NOT in this round
Tier 2/3 of `AUDIT-2026-07.md` (exercise media, RPE/set types, manual mid-workout editing,
Health Connect, export, periodization schema, post-workout AI debrief) and the audit's smaller
polish list (resume-strip midnight filter, deep-link `launchSingleTop`, cleartext in release,
dead `nextProgramDay` fetch, stale "four destinations" KDocs).

## Audit Resolution Log — Tiers 2 & 3 (2026-07-28)

The rest of `AUDIT-2026-07.md`: Tier 2 ("table stakes vs Hevy/Strong") and Tier 3 (the
differentiators). Server **293 pytest green** + `ruff check app` clean (Alembic now **15
revisions**); Android **369 unit tests green** + `:app:assembleDebug`. On-device pass still owed.

**One new dependency** (per the "ask first" rule, approved for this round):
`androidx.health.connect:connect-client:1.1.0` — stable, `minSdk 26`, matching Spotter exactly.

### Server
- **Exercise detail** (migration `0013`) — `instructions` + `secondary_muscles`, backfilled for
  all **81** seeded movements; `GET /exercises/{id}`.
- **RPE + set types** (`0014`) — `rpe`, `set_type` (`normal|warmup|drop|failure|amrap`).
  **Warm-up sets are excluded from volume, progression inputs, est-1RM and PR detection** — that
  exclusion is the whole point of the concept, so any future computation over sets must filter it
  too (now ARCHITECTURE.md invariant #6). Plus `DELETE /sessions/{id}/sets/{set_id}`.
- **Periodization** (`0015`) — programs gain `created_at`/`source`/`description`/`weeks`/
  `deload_week`/`started_on`; activation stamps `started_on`; `current_week` cycles
  (`((today - started_on).days // 7) % weeks + 1`) so a mesocycle repeats rather than running off
  the end. A deload week seeds `ceil(sets × 0.6)` at `weight × 0.9`. The same pure helper feeds
  both session seeding and the read path, so they can't disagree. `routine_exercises.rest_seconds`
  lands here too. **[AI prompt change]** the coach may author `weeks`/`deload_week` through the
  existing extract-and-clamp path — a richer suggestion shape, not a new write power; accept
  gained `activate`, `source`, `description`.
- **New endpoints** — `GET /insights` (stalled lifts + PRs this week, reusing
  `progression.stalled_sessions()` rather than a second stall implementation),
  `POST /ai/sessions/{id}/debrief`, `GET /ai/recap/weekly`, `GET /export/sets.csv`. The three LM
  callers now share `client.lm_completion()`, so 502/503/504 mapping is identical everywhere.
- **A PR requires a prior best to beat** — a new exercise's first session sets a baseline, not a
  wall of records (invariant #7).

### Android
- **Workout mode** — set types (tap the set number; badge replaces it), opt-in RPE entry, set
  deletion, manual add/remove exercise mid-session, rest ±15s, auto-start toggle, and
  per-exercise rest overrides used verbatim (an explicit prescription gets no failure bump).
- **Offline set deletion uses a `pendingDelete` tombstone** (Room v14) — a plain local delete
  would be resurrected by the `getSession` merge, which re-adds server rows missing locally.
- **Exercise detail** — instructions/muscles from the catalog mirror (offline-capable) plus a
  server-computed history chart and PR panel loaded independently, so a history failure never
  takes the instructions down. Reachable from the Library and the workout card's exercise name.
- **Presets** — preview screen with **add-and-activate or add-without-activating**, and real rest
  days. Presets define the smallest repeating **cycle** (`A, Rest, B, Rest`), not a 7-day week,
  because `accept_program` creates one routine per training day and a repeated day would create
  duplicate routines; `presetCadenceLine` derives the advertised frequency from the day list so
  the description can't drift from the schedule again. A day whose exercises all fail to resolve
  is dropped, never sent empty — an empty day now *means* rest.
- **Periodization display** — "Week N of M" + an amber DELOAD WEEK badge. The client recomputes
  week/deload from the mirrored `started_on` rather than caching the server's time-derived
  `current_week` (a cached copy would be stale by definition).
- **Templates** — "Repeat this workout" and "Save as routine" from a completed session.
- **AI surfaces** — a post-workout coach debrief on the summary (**omitted entirely on failure**;
  LM Studio being down is the normal case here, not an error worth showing), a weekly recap
  screen, and a Home "coach signals" card from `/insights` (best-effort: no card, no error).
- **Export + Health Connect** — Settings → Export data (JSON/CSV via the share sheet) and
  opt-in, **write-only**, default-off Health Connect mirroring of sessions + bodyweight.

### Still deferred (deliberate)
- **Cardio GPS/distance/pace** — needs real device sensors; unverifiable in CI, so not started.
- **Exercise demo images/video** — no licensed media to ship, and this repo's bar is "delete beats
  build"; the instructions backfill is the honest version of that feature. Revisit only with a
  real asset source.
- Manual editing of a program's periodization after accept (set at accept time only), and the
  audit's smaller polish list (resume-strip midnight filter, deep-link `launchSingleTop`,
  cleartext in release).

## Postpartum return-to-training round (2026-07-28)

Made the app genuinely usable by someone coming back to training after giving birth. Server
**300 pytest green** + `ruff check app` clean; Android **371 unit tests green**.

**The real gap wasn't the presets — it was the coach.** A "Postpartum Rebuild" preset had shipped
since 2026-06-10, but `prompts.py` contained **zero** postpartum, pregnancy, or pelvic-floor
content. Ask the coach "I just had a baby, build me a program" and it treated her as a generic
beginner: 5–6 exercises, 30–60 minutes, crunches and heavy compounds on the table. The preset was
safe; the app's flagship surface — and the most likely first touchpoint — was not.

- **[AI guardrail/prompt change] New "Pregnancy and Postpartum — Treat as a Primary Constraint"
  section** in `prompts.py`. It stays inside the app's non-medical scope (exercise selection +
  referral, never diagnosis):
  - **Clearance is the clinician's to give, never the model's** — no healing-timeline estimates,
    no "you're ready", no second-guessing a doctor or midwife.
  - **Stop-and-refer symptoms** (leaking, pelvic heaviness/bulging, abdominal doming or coning,
    pain incl. around a C-section incision, renewed bleeding) → stop that movement and see a
    doctor or **pelvic floor physiotherapist**, named explicitly because most people don't know
    that referral exists. The model must not program around them or explain what they mean.
  - **C-section is abdominal surgery** — extra conservatism, surgeon's guidance wins.
  - **Early exercise selection**: prefer hips/glutes, supported pulling, controlled range and
    split stance; avoid spinal-flexion/rotation core work, long anti-extension holds, Valsalva-
    heavy and maximal lifting, and impact until well re-established and symptom-free.
  - **Session sizing explicitly overrides the 5–6 exercise / 30–60 minute rule** → 3–4 exercises,
    20–30 minutes, 2–3×/week, and never guilt-trip a short or missed session.
  - The same posture is wired into **live in-workout adjustments**: a postpartum symptom is never
    answered with a lighter load — it's a removal plus a referral.
- **Presets restructured into two stages** (coming back is progressive; one flat program either
  starts too hard or stays too easy): **"Postpartum — First Weeks Back"** (2×/week, ~20 min,
  mostly bodyweight, hips + supported pulling) → **"Postpartum — Rebuilding Strength"** (3×/week,
  light loaded, hinge reintroduced). Both descriptions defer to her clinician and name the pelvic
  floor physio referral.
- **Dropped the plank from the early preset.** It sat in a program advertising itself as
  "core- and pelvic-floor-friendly, no crunches" — a long high-load anti-extension hold is
  exactly what that stage is not for. (It was also encoded `3 × 1`, rendering as a 1-rep set.)
- **New guardrail tests.** `ProgramPresetsTest` now asserts the pregnancy/postpartum presets
  contain none of a contraindicated-movement list and that every postpartum description defers to
  a clinician and names the physio referral — so nobody can later "helpfully" add a crunch back.
  `tests/test_ai_postpartum.py` pins the prompt content, the session-size override, the
  live-adjustment referral rule, and — importantly — that messages like "I'm postpartum and
  leaking a bit when I squat" are **not** blocked as out-of-scope, which would silently make the
  coach useless for exactly this user.

**Test-hygiene fix (unrelated, found while verifying):** `tests/test_suite_auth.py` used fixed
emails with `assert count == 0`, so the suite only passed against a virgin database and reported
two false failures on any re-run. Now uses a unique address per run; the suite passes twice in a
row against the same DB.

**Not done, deliberately:** nothing here estimates recovery timelines, judges readiness, or
interprets symptoms — that's her doctor's and pelvic floor physio's job, and the app says so.

## Equipment memory fix — persistent training profile (2026-07-28)

**Reported as "the AI keeps forgetting what equipment I have."** It wasn't the model forgetting;
the equipment was never stored anywhere the coach could trust or the user could edit. Server
**320 pytest green** + ruff clean; Android **386 unit tests green** + `assembleDebug`.

### Why it happened (three compounding causes)
1. The training profile (experience/goal/**equipment**/age group/limitations) lived **only** in
   Android DataStore and reached the model **only** as `ChatRequest.user_context` — a
   client-supplied string `_merged_context` deliberately treats as "stated preferences", never as
   trusted context. The server had no idea what equipment existed.
2. **Onboarding almost never runs.** `AuthViewModel.login()`/`completeSuiteLogin()` mark
   onboarding done unconditionally (deliberate — a returning user on a fresh install shouldn't be
   re-onboarded), so only the Register path fills the profile. Everyone else had an empty
   `UserProfile`, whose `toContextString()` returns `""` → sent as `null`.
3. **Nothing could edit it after onboarding.** `OnboardingViewModel.finish()` was the only writer
   in the app, so new equipment could never be recorded.
   (`users.settings` existed but was dead — only ever assigned `None`.)

### Fixed
- **Migration `0016`** — `users` gains `equipment`/`experience`/`goal`/`age_group`/`limitations`/
  `profile_updated_at`; the dead `settings` column is dropped in the same revision.
- **`GET`/`PATCH /users/me/profile`** (30/min). PATCH is partial via `model_fields_set`: an
  omitted key is unchanged, an explicit `""`/`null` clears it, and `PATCH {}` is a true no-op so a
  never-filled profile stays distinguishable from a deliberately cleared one. Account reset clears
  it.
- **The actual fix:** `context_service.build_user_context` now leads the trusted DB-derived block
  with the saved profile, so equipment reaches the model on every chat regardless of what the
  client attaches. This also fixed a **second instance of the same bug class** — the function
  early-returned `None` when the user had no training history, so a saved profile would never have
  reached the model for anyone who hadn't logged a session yet.
- **[AI prompt change]** the Training profile block is authoritative and persisted: never re-ask
  its items, program within the listed equipment, and honour an in-chat contradiction ("hotel gym
  this week") for that conversation without re-interrogating everything else.
- **Settings → Training profile** — equipment first and multi-line, then experience/goal/age as
  chip groups mirroring onboarding's values, then limitations. `ProfileRepository` keeps the
  server authoritative with the DataStore mirror for offline; `refresh()` drains before pulling so
  a sync can't clobber an offline edit; onboarding now pushes to the server too, and the Home sync
  round + reconnect observer pull it back (chosen over touching `AuthViewModel`, where a hang
  would break sign-in).

### Deliberately not done
The coach cannot write to the profile itself. "I bought a squat rack" mid-chat does **not**
silently update stored equipment — that would break the "AI proposes, user commits" invariant.
If that's wanted, it should be a confirm card like every other AI write.

### Still open (unchanged, noted for later)
`login()`/`completeSuiteLogin()` still mark onboarding done unconditionally, so most users never
see the questionnaire. Much less harmful now that Settings can edit the profile and the server
copy repopulates on sign-in, but the root behaviour stands — fixing it means touching the auth
flow, which is the riskiest possible place for this.

## Program-creation fixes + evening motivation nudges (2026-08-10)

Two streams from the program-creation review: the critical correctness fixes, and two new
opt-in local notifications. Server **371 pytest green** (migrated scratch DB) + `ruff check
app` clean; Android unit suite + `assembleDebug` green.

### Program-creation fixes
- **[HIGH][Android] AI periodization now round-trips.** `SuggestedProgram` gained
  `weeks`/`deload_week` and both accept call sites (coach Save-Program card, first-run
  auto-generate) forward them — the client used to drop them silently, so the whole
  deload/mesocycle feature (server-side since migration `0015`) was unreachable via the coach,
  its only author. The Save Program card subtitle now shows the block ("3-day program ·
  6-week block, deload wk 6") so the user sees what they're accepting.
- **[HIGH][Server] `accept_program` is one transaction.** `create_routine` /
  `create_program` / `update_program` take `commit: bool = True`; accept passes
  `commit=False` (flush-only) and lands a single commit at the end, so a mid-sequence
  failure rolls back instead of stranding orphan `source="ai"` routines (retry used to
  duplicate them). Also truncates the composed routine name (`"{program} — {label}"` could
  hit 358 chars vs the 255 column → 500).
- **[MED][Server] Program days validate routine ownership** (`_verify_routines_owned`):
  a nonexistent or another user's `routine_id` in `POST /programs` / `PUT /programs/{id}/days`
  is a clean 422 (was: silent foreign link or FK 500). Audit item `AUDIT-2026-07.md:198`.
- **[MED][Server] Length/count caps**: `PROGRAM_NAME_MAX_LEN=255`,
  `PROGRAM_DAY_LABEL_MAX_LEN=100`, `ROUTINE_NAME_MAX_LEN=255`, `MAX_PROGRAM_DAYS=14` in
  `limits.py`, enforced via `Field` caps on the program/AI/routine write schemas (422, not a
  DB error); the AI extraction layer truncates names/labels and slices the day list instead
  of dropping the program (clamp-not-reject, as everywhere else).
- **Doc drift fixed:** `database.py` has no `DB_NULLPOOL` switch (the env var is a no-op);
  conftest's session-scoped event loop is what actually prevents the cross-loop asyncpg
  errors. ARCHITECTURE.md corrected.

### Evening motivation nudges (Android-only, local, opt-in)
Behind the existing single Reminders toggle, a second daily WorkManager worker
(`EveningNudgeWorker`, ~18:00) joins the morning nudge, evaluating two mutually-exclusive
kinds via pure `EveningNudge.decide` (same conventions as `WorkoutNudge`: quiet hours,
fire-time re-checks, Context-free copy):
- **Streak-saver** (id 1004): today is a scheduled workout day, nothing logged by evening,
  and there's a live streak — "Your N-day streak is on the line." Streak math extracted from
  HomeViewModel into pure `util/StreakCalculator` (shared, so the notification's number is
  Home's number; `HomeViewModelTest` pins behavior-identical extraction).
- **Comeback** (id 1005): fires once per lapse when 2–3 scheduled workout days were missed
  (`StreakCalculator.consecutiveMissedWorkoutDays`, rest-day-aware for calendar programs,
  slot arithmetic for cadence programs; a never-trained or long-lapsed user falls outside
  the window). Latched per miss episode via `AppPreferences.comebackNudgeAnchor` (the
  last-completed date at posting) — training again re-arms it. Magpie-style alert latching.
- Notification plumbing deduplicated into `NudgeNotifications` (one channel
  `spotter_nudge`); scheduler now enqueues/cancels both unique works. Tests:
  `EveningNudgeTest`, `StreakCalculatorTest`, extended `WorkoutNudgeSchedulerTest`.

### Deliberately not in this round (from the review, still open)
Manual program-builder UX rework (name-only dialog → routine-dropdown scavenger hunt), full
exercise detail in the AI preview card, unsaved-changes guard on ProgramDetail, persisting
`program_day_id` on `WorkoutSession` (the `get_next_day` heuristic stands), per-kind
notification channels/configurable nudge hours, and cardio/strength program unification.

## Return-to-lifting round (2026-08-10)

The program-content review's top gap: the months-scale returner — the most common and most
injury-prone lifter — had no path (constraint presets only, no coach guidance). Mirrors the
postpartum pattern: staged presets + a prompt section + guardrail tests for both. Server
**368 pytest green** + ruff clean; Android unit suite green (449).

- **Presets:** "Back After a Break — Restart" (`returning_restart`) → "Back After a Break —
  Ramp Up" (`returning_ramp`), placed after Full Body in the mainstream block. Stage 1: full
  body every other day, 5 lifts × 2 sets, bar-plus-a-little loads (squat/bench/row 65,
  OHP 45 — strictly below Full Body's on every shared lift, because tendons regain load
  tolerance far slower than muscle memory returns strength). Stage 2: 6 lifts × 3 sets,
  loads one step below Full Body; description graduates to Full Body / StrongLifts /
  Upper-Lower by name. Descriptions carry the returner coaching (old numbers are a memory,
  the tendon lag, the first-session DOMS warning — the #1 reason returners quit). Guardrail
  tests (`ProgramPresetsTest`) pin the staged handoff, the coaching phrases, a
  **relationship-based load ramp** (stage1 < stage2 < Full Body on shared lifts — no
  brittle absolutes), and stage 1's ≤3.5/week frequency.
- **[AI prompt change] "Returning After a Layoff — Months or More"** in `prompts.py`:
  a stated months+ layoff is a primary programming input; restart at ~50–60% of remembered
  loads ("program for the tendons, not the ego"); ~2 working sets first 1–2 weeks; warn about
  delayed soreness *before* it happens; ride the fast re-acquisition progression with a rep
  in reserve; never guilt the layoff. Its session-sizing paragraph says "**relaxes** the
  session-size rule" — deliberately distinct from the postpartum "**overrides**" phrasing;
  `test_ai_returning.py` pins both directions (`count("overrides ...") == 1`) so an edit
  can't alias the two prescriptions. Scoped against the existing Adaptive-Coaching
  "~70% for illness/short time off" rule so the model never holds two restart fractions.
  **Deliberately NOT an Intake Protocol item** — intake asks only for missing profile facts,
  and a standing "when did you last train?" would interrogate every user forever; the
  section's conditional ask covers the real case.
- Prompt tests (`tests/test_ai_returning.py`, mirrors the postpartum file): guidance present
  and reaches the model via `build_messages`, restart fraction pinned, realistic returner
  messages (`"my bench used to be 225 but I've been off for a year"`) not blocked by
  `validate_request`, replies survive `validate_response`, mocked end-to-end `/ai/chat`.

### Drive-by fix found by the suite: cardio dates parsed in UTC, not local
Running the suite in the evening (8pm EDT = past midnight UTC) exposed a real bug, not a
flake: `CardioFormat.parseDate` resolved ISO instants to the **UTC** calendar day while every
caller compares against **local** dates — so for anyone west of Greenwich, a cardio run
started after ~8pm couldn't be resumed (`ActiveCardioStore`'s today-filter missed it) and an
evening run's streak/stats credit landed on tomorrow (`HomeViewModel`, `CardioSchedule`, the
evening nudge worker). Fixed to `ZoneId.systemDefault()`; manual entries' noon-UTC anchor
still buckets correctly for any offset within ±11h. This also **retires the documented
"CardioScheduleTest is timezone-sensitive" flake** — the test's midnight-UTC fixtures were
the same bug in miniature; they now encode local midnight and round-trip exactly in any zone.

## Settings overhaul + configurable nudge times (2026-08-11)

On-device screenshots showed Settings failing four ways at once, all of them consequences of
hand-rolled rows: the nudge description ran **underneath** its toggle, the quiet-hours stepper
pair measured ~406dp into a ~329dp card so the trailing `+` clipped off-screen, training-profile
chips broke *inside* words ("Intermediat/e"), and the copy read "A 8 AM". Android **463 unit
tests green** + `assembleDebug`. Server untouched.

### The rows now come from Pulse (v1.1.0)
`PulseSwitchRow` / `PulseSettingRow` / `PulseTimeRow` / `PulseStepperRow` were promoted into the
shared library (Pulse PR #17) — four consumers had four spellings of the same rows, and Plate's
and Magpie's `HourStepper` were byte-identical. Pulse now sets a **48dp minimum row height**.
Spotter's private re-implementations of `ProfileHeader` and `SettingsSection` — which Pulse had
*extracted from Spotter* and Spotter never adopted back — are deleted.

### Stateless split (the reason the defects shipped)
`SettingsScreen.kt` is now nav + one-shot effects only (~230 lines, was 972); everything it
renders is `SettingsContent.kt` — `SettingsUiState` / `SettingsActions`, both fully defaulted,
plus one `*Block` per group. **The old `settings_*` Roborazzi baselines rendered a hand-built
lookalike that shared no code with the real screen**, so they could never have caught any of
this. The scenes now drive the real `SettingsContent`, plus two new ones covering exactly the
regressions (`settings_reminders_*`, `settings_profile_*`).

### Nudge times are user-settable
Morning reminder and evening streak-saver each get a tap-to-open Material 3 time picker, as do
both ends of the quiet window; `NUDGE_HOUR`/`EVENING_NUDGE_HOUR` become *defaults*. **Minutes are
persisted, not rounded** — the picker always shows them, and storing only the hour would display
a time the app doesn't honour; `WorkoutNudge.isQuietTime` works in minutes-since-midnight with the
hour-only overload delegating, so the existing nudge tests stay green verbatim.
**The scheduling subtlety that made this non-trivial:** WorkManager's `KEEP` policy treats an
existing periodic work as satisfying the request and silently drops the new initial delay, so
moving a reminder would appear to work and then keep firing at the old time. `sync()` now reduces
the schedule to a signature — unchanged ⇒ `KEEP` (still self-heals a dropped work, without
resetting the running 24h window), changed ⇒ `CANCEL_AND_REENQUEUE` — and `SpotterApp` observes
enabled+both times together via `combine`.

### Section regroup (13 → 9)
Workout (absorbs the lone-stepper "Schedule") · Reminders · Programs · Training profile ·
Library & data (absorbs "Export data") · Appearance & units · Connections (Health Connect +
server URL) · Account · About. Training profile moved **down** from first: it's the only long
form, and leading with it pushed every tap-only section below the fold. **About keeps its own
section deliberately** — CLAUDE.md and `deploy/README.md` both name "Settings → About" as the
deploy-verification step.

### Chips
`ChoiceRow` → `FlowRow` (`@OptIn(ExperimentalLayoutApi::class)`, the idiom already used in Plate/
Cookbook/Magpie on this BOM). The old version faked flow layout with `chunked(perRow)` +
`weight(1f)`, forcing equal content-independent widths — which is what broke "Intermediate"
mid-word. Its KDoc had explicitly justified rejecting flow layout; that reasoning is reversed and
rewritten in place.

### Test-coverage note
`SettingsViewModelTest.setup()` never stubbed `trackRpe`, `autoStartRest`, `workoutNudgeEnabled`
or the quiet-hours flows — construction survived only because `stateIn(WhileSubscribed)` defers
collection, so the first test to read one would have NPE'd. Stubbed now, with cases added for
every previously-untested setter (nudge times incl. minutes, quiet window atomicity, RPE,
auto-rest, theme, units).

### Known cosmetic, pre-existing
Selected `FilterChip`s render in M3's `secondaryContainer` (green) rather than Spotter's blue —
unchanged by this round (the old chips used the same defaults), but it reads off-brand next to the
blue primary button. Worth a look if the training-profile form gets another pass.
