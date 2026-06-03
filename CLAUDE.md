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
1. **AI chat** — conversational workout-plan setup. See AI Guardrails below.
2. **Workout mode** — per-exercise list with a target header (e.g. `8×115lb`, `3×8 BW`). Each set is a tap-to-complete control showing its reps; tapping marks it done (filled vs. dim states). Weight is logged per set beneath it and can differ across sets. Supports a "+" to add sets, bodyweight ("BW") exercises (no weight), a running session timer, per-exercise notes, and an inline edit mode. Must work offline.
3. **Calendar** — view/track scheduled and completed workouts by date.
4. **Progress tracking** — persist weight (bodyweight and/or per-exercise load) and reps over time; expose for charting.
5. **Programs** — multi-day programs (`WorkoutProgram` → ordered `ProgramDay`s, each linking a plan) with a "next day" suggestion on Home.
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
- **Structured output:** when generating a plan, require the model to return JSON matching the `WorkoutPlan`/`PlannedExercise` schema. Validate with Pydantic before persisting; reject and re-prompt on malformed output.
- **Input handling:** treat user chat as untrusted. Guard against prompt injection (e.g. "ignore previous instructions") — the system prompt and validation layer take precedence.
- **Sanity bounds:** the canonical bounds live in `app/limits.py` and are enforced two ways — Pydantic `Field(ge/le)` constraints on the write schemas (`PlannedExerciseIn`, `SetLogCreate/Update`) reject out-of-range client input (422), and the AI plan-extraction layer (`client._extract_plan`) *clamps* whatever the model returns into bounds rather than dropping the plan.
- **Trusted context:** `app/services/ai/context_service.build_user_context` derives a short training-history summary from the DB (recent sessions, last weights, current plan, bodyweight trend) and injects it into the system prompt as trusted context. Any client-supplied profile string is appended as stated preferences only — it never overrides the DB-derived data.
- **No tool/system access:** the LLM proxy has no file, shell, or DB write access; it only returns text/JSON that the server validates and stores.
- Keep the prompt + guardrail logic in one module (`app/services/ai/`) so it's auditable in isolation.

## API Surface
- `POST /auth/register|login|refresh|forgot-password|reset-password`
- `GET/POST /plans`, `GET /plans/{id}`, `PATCH/DELETE /plans/{id}`, `PUT /plans/{id}/exercises`
- `GET/POST /sessions`, `GET/PATCH /sessions/{id}`, `POST/PATCH /sessions/{id}/sets[/{set_id}]`, `GET /sessions/{id}/prior-bests` (includes progression-aware `suggested_weight`)
- `POST /ai/chat` — proxies to LM Studio, applies guardrails + trusted context, returns reply (+ optional validated plan)
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

## Known Issues & Audit Backlog
Findings from a full-codebase audit (2026-06-03). Each item lists the location and the
intended fix. None are fixed yet — treat this as the prioritized work queue. The two
HIGH server items produce 500s against real Postgres; the two HIGH Android items break
the core offline workout path.

### Server (FastAPI)
- **[HIGH] Password reset is broken — token column too small.** `models/user.py:18` and
  migrations `0003`/`0006` define `reset_token` as `VARCHAR(6)`, but
  `auth_service.forgot_password` writes `secrets.token_urlsafe(32)` (~43 chars). Postgres
  raises `StringDataRightTruncation` → 500, so `forgot-password`/`reset-password` never
  work. *Fix:* widen the column to `String(64)`/`Text` via a new migration + update the
  model. Add a test covering the full reset flow (currently untested).
- **[HIGH] `DELETE /plans/{id}` 500s for any plan with sessions.** `workout_sessions.plan_id`
  FK has no `ondelete` (migration `0001:92`, model `workout_session.py:16`), so deleting a
  referenced plan raises an IntegrityError. *Fix:* change the FK to `ondelete="SET NULL"`
  (matching `program_days.plan_id`) via migration, or null/detach sessions in
  `plan_service.delete_plan` first.
- **[MED] Prompt-injection guard only checks the last user message.** `ai/client.py:25-34`
  validates `last_user` but forwards `messages[:-1]` verbatim. Injection placed in an
  earlier turn bypasses `_BLOCKED_PATTERNS`. *Fix:* run `validate_request` over every
  prior `role == "user"` turn (ideally sanitize `assistant` history too).
- **[MED] `add_set` doesn't validate `exercise_id`.** `session_service.add_set:230` inserts
  directly; a bogus id triggers an FK IntegrityError → 500. *Fix:* look up the `Exercise`
  first and return 404/422 if missing.
- **[MED] `BodyMetric.weight`/`bodyfat` have no sanity bounds.** `schemas/metric.py:9`
  accepts negative/absurd values that then feed the trusted AI context and progress.
  *Fix:* add `Field(gt=0, le=...)`, sourcing a range from `app/limits.py`.
- **[LOW] Over-broad injection regexes.** `ai/prompts.py:288,293,296` false-positive on
  legit chat ("act as a spotter", "life hack", word-boundary "ped"). *Fix:* require an
  instruction-override context.
- **[LOW] `get_next_day` mishandles duplicate plan_ids across program days.**
  `program_service.py:176`/Android `WorkoutProjection.kt:85` match by `plan_id` via
  first-occurrence, so two days sharing a plan rotate incorrectly. *Fix:* track the last
  performed `ProgramDay` id on the session rather than matching by plan.
- **[LOW] `get_exercise_progress` takes `max(weight)` and `max(reps)` independently**
  (`progress_service.py:40-58`) — the reps shown may not be the reps at the top weight.
  Fine for separate charts; revisit if used for est-1RM trend.

### Android (Kotlin/Compose)
- **[HIGH] Offline-added sets vanish on reload for synced sessions.**
  `SessionRepository.getSession:97-114` rebuilds set logs by mapping only over
  `serverResult.setLogs`, dropping local sets with `serverId == null`. *Fix:* append
  unmatched local logs (`serverId == null` / `syncPending`) after the server map.
- **[HIGH] No Room `MIGRATION_1_2` and no `fallbackToDestructiveMigration()`.**
  `SpotterDatabase` is `version = 4`; `AppModule:86` registers only `2_3`/`3_4`. A DB at
  schema v1 crashes on launch. *Fix:* add `fallbackToDestructiveMigration()` (Room is a
  server mirror) and/or the missing migration.
- **[HIGH] No reconnect-triggered sync.** `syncPending()` runs only from Home; there is no
  `ConnectivityManager`/WorkManager trigger, and step 2 skips sets with `serverId == null`.
  Edits to a never-synced session have no independent path to the server if Home isn't
  opened. *Fix:* register a network callback / WorkManager job that calls `syncPending()`
  on reconnect.
- **[MED] `volumeLb` truncates each set before summing.** `WorkoutViewModel.kt:250` does
  `sumOf { (reps * weight).toInt() }`. *Fix:* sum as Double, round once.
- **[MED] `WorkoutSummaryStore` is a mutable global** (`WorkoutViewModel.kt:40`) — muscle
  groups and PR count are lost on process death. *Fix:* pass via SavedStateHandle / route.
- **[MED] Transient two-active-programs window.** `activateProgram` upserts only the new
  program locally; `getActive()` uses `LIMIT 1` with no ordering. *Fix:* clear other active
  flags locally in the same transaction.
- **[MED] Most non-workout writes have no offline path** (metrics, plans, programs,
  calendar, exercise search throw on failure; `logBodyweight` swallows the error silently).
  *Fix:* write-through to Room + queue for sync where reasonable; surface swallowed errors.
- **[MED] `listSessions` offline fallback drops plan name + set counts**
  (`SessionRepository.kt:238-251`). *Fix:* populate counts from `setLogDao` and resolve
  plan name from cache.
- **[LOW] `PlannedExerciseEntity` PK `(planId, exerciseId)`** forbids the same exercise
  twice in a plan. *Fix:* use a surrogate/`(planId, order)` PK to match the server.
- **[LOW] Plate calculator** drops non-representable remainder and uses exact float
  equality; input filter allows multiple dots. *Fix:* snap to nearest achievable increment,
  show residual, guard input.
- **[LOW] `RestTimerService` is a plain started service** (no `startForeground`,
  `POST_NOTIFICATIONS` not requested) — the rest countdown can be killed in the background.
  *Fix:* foreground service + runtime notification permission.
- **[LOW] No delete-session path.** `WorkoutSessionDao.deleteById` exists but is unused;
  there's no `DELETE /sessions/{id}` endpoint or UI. *Fix:* add the endpoint + repo/VM/UI.

### Verified OK (not bugs)
- AI plan extraction **clamps** out-of-range model output rather than dropping the plan;
  write schemas enforce the same bounds via Pydantic. Rate limits match this doc exactly.
- All user-data endpoints require auth and filter by `user_id`; refresh-token type checks
  are correct. `estimatedOneRM` (client) matches `_epley_1rm` (server).
- Android token-refresh authenticator loop-guard, host-selection interceptor ordering, and
  the work/rest timer flow are sound.
