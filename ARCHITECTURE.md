# ARCHITECTURE.md — Spotter (software-level)

How this codebase is organized and why — the doc to read before your first change. Suite-level
context lives in `C:\Code\ARCHITECTURE.md`; working instructions + as-built history in
[CLAUDE.md](CLAUDE.md); prioritized debt/features in [ROADMAP.md](ROADMAP.md).

Spotter is the **oldest** app in the suite and the **reference implementation** for the AI
guardrail model and most conventions — but Cookbook carries the newest infrastructure
conventions; when scaffolding something new, check how Cookbook does it.

## System shape

```
Android (Kotlin/Compose, offline-capable) ⇄ FastAPI :8000 ⇄ Postgres :5432
                                                 │
                                                 └→ LM Studio :1234 (host.docker.internal, chat + plan/program/adjustment extraction)
Cross-app: Plate ─GET /workouts?date=→ Spotter (training-day status)
```

## Server (`server/`)

### Layers

`app/routers/` (HTTP, thin — auth dependency, rate limit, delegate) → `app/services/` (business
logic, one service per domain) → `app/models/` (SQLAlchemy 2.0 async) with `app/schemas/`
(Pydantic) validating every request/response. Cross-cutting: `security.py` (JWT session +
`get_cross_app_user`), `limiter.py` (slowapi), `limits.py` (canonical numeric bounds — see
Guardrails), `progression.py` (pure progressive-overload engine — double progression / deload /
e1RM, table-tested, no I/O; feeds `session_service.get_prior_bests`), `config.py`
(pydantic-settings), `database.py` (asyncpg engine; NullPool under tests via `DB_NULLPOOL`).

### Domain map (router → service → models)

| Domain | Router | Service | Models |
|---|---|---|---|
| Auth/users | `auth.py`, `users.py`, `suite_auth.py` | `auth_service`, `suite_auth` | `User` |
| Routines (the user-facing "plans") | `routines.py` | `routine_service` | `WorkoutRoutine`, `RoutineExercise` |
| Programs (multi-day) | `programs.py` | `program_service` | `WorkoutProgram`, `ProgramDay` |
| Sessions + sets | `sessions.py` | `session_service` (+ `progression.py` for `/prior-bests`) | `WorkoutSession`, `SetLog` |
| Cardio | `cardio.py` | `cardio_service` | `CardioSession` (guided/free live runs **+** `POST /cardio/sessions/manual` after-the-fact walk/run entries: `program_id="manual"`, completed, optional `activity_type`/`distance_meters`) |
| Metrics/progress/calendar | `metrics.py`, `progress.py`, `calendar.py` | matching services | `BodyMetric` + reads over sessions |
| Exercise catalog | `exercises.py` | — (seeded reads) | `Exercise` |
| AI | `ai.py` | `services/ai/` (see below) | writes via other services only |
| Cross-app | `workouts.py` | `workout_service` | reads sessions/cardio |
| Export | `export.py` | `export_service` | generic `__table__.columns` dump |

**Naming trap:** the original "plan" concept was renamed to **routine** (`/routines`,
`WorkoutRoutine`). The AI schemas still say `SuggestedPlan` — that's the AI-draft object, not a
DB row, so the name is intentional. (Historical changelog entries in CLAUDE.md still reference the
old `/plans` path and the `plan_id` column by design — they describe past fixes.)

### The AI module (`app/services/ai/`) — the part you must not casually edit

All prompt + guardrail logic is deliberately confined here so it can be audited in isolation:
- `prompts.py` — the server-side system prompt (intake protocol, plan/program JSON rules,
  live-adjustment rules, scope limits). Client never supplies prompts.
- `client.py` — LM Studio transport + extraction: `_extract_plan` / `_extract_program` /
  `_extract_adjustment` parse the model's JSON, resolve exercise names → catalog ids, and
  **clamp** out-of-range numbers into `app/limits.py` bounds (clamp, never drop). Malformed
  responses → 502; unreachable LM Studio → 503. Injection screening runs over *every* user turn.
- `context_service.py` — trusted context from the DB (training history; live-session summary
  when `current_session_id` is given). Client-supplied profile text is appended as stated
  preferences only — it never overrides DB-derived facts.
- `adjustment_apply.py` — the only path an AI suggestion touches the DB, and only via
  `POST /ai/sessions/{id}/adjust` after an explicit user Apply. **Completed sets are immutable**;
  only incomplete sets are ever mutated, in one transaction.

The trust model, everywhere: **AI proposes, user commits.** There is no autonomous write path;
adding one is an architecture change, not a feature.

Bounds are enforced twice by design: Pydantic `Field(ge/le)` on write schemas rejects bad client
input (422); the extraction layer clamps bad model output. Both source from `app/limits.py`.

### Migrations & tests

Alembic (12 revisions), auto-applied on container boot (`docker-entrypoint.sh`). 27 pytest files
(~220 tests) cover routers, the guardrail/extraction layer (LLM mocked), bounds, and cross-app
auth. Local run: throwaway DB + `DATABASE_URL` on **127.0.0.1** + `DB_NULLPOOL=1` (see CLAUDE.md
"Local pytest recipe"). Known flake: `CardioScheduleTest` (Android) is timezone-sensitive.

## Android (`android/`, package `com.spotter`)

### Data flow

`ui/<feature>/` Composables → ViewModel (StateFlow, sealed UI state) →
`data/repository/` → Room (`data/local/`: dao/entity) + Retrofit (`data/remote/`). Hilt in `di/`.
Repositories decide local-vs-remote and own sync.

### Offline model (the app's defining constraint)

**Writes are offline-capable across the app** via a shared write-through + drain-queue pattern:
each repository writes to Room with `syncPending` first, then `NetworkSyncObserver` (connectivity
callback registered in `SpotterApp.onCreate`) drains the pending work on reconnect.
- **Workout mode** (sessions/sets): `SessionRepository` reconciles server + unsynced-local rows
  (and does a wholesale reconciliation after AI adjustments, because swaps/removes delete server
  rows).
- **Bodyweight metrics, routines, and programs** are offline-editable through the same
  write-through queue (`MetricRepository`, `RoutineRepository`, `ProgramRepository`); the sync
  step translates offline-created routine ids to server ids on push so program-day references
  reconcile without duplicating.
- **Calendar** serves the last-known projection on an offline read instead of throwing.
- **Cardio** writes are local-first with best-effort push, dedupe-safe.

Room is a server mirror: destructive migration is acceptable and configured. (Remaining offline
gap: an offline-finished workout still shows no muscle-group breakdown — `muscle_group` isn't
cached locally; ROADMAP follow-up.)

### Timers (unified Sprint 8 model — keep it)

All timers are drift-free and background-correct: monotonic `elapsedRealtime` anchors, never tick
counters. `util/TimeProvider.kt` is the injectable clock seam. `WorkoutTimerController`
(@Singleton) owns rest countdown + work count-up (generation-guarded, holds its own wake-lock,
app-scoped coroutine so cues fire while backgrounded); the session elapsed clock recomputes from
the persisted `startedAtMs` anchor. The rest countdown also **survives process death**:
`WorkoutTimerController` persists the wall-clock rest end-anchor via `RestTimerStore` (DataStore)
and restores it on init, so reopening mid-rest resumes exactly (and a rest that elapsed while the
app was gone is cleared, its cue moment passed). `CardioRunController` is the cardio equivalent
and was the original gold standard. One foreground service per feature (`WorkoutSessionService`,
`CardioRunService`) sharing `util/ForegroundServiceSupport.kt`. Do not reintroduce `delay(1000)`
counters.

### Feature packages worth knowing

- `ui/workout/` — the core product surface (set logging, rest panel, edit mode, resume strip via
  `util/ActiveWorkoutStore`). Supersets render as visually paired rows under a "SUPERSET A/B"
  header driven by the routine's `supersetGroup` (shared rest); the server derives the grouping,
  the client only displays it.
- `ui/ai/` — coach chat + the three suggestion cards (plan / program / live adjustment).
- `ui/program/ProgramPresets.kt` — client-side preset programs (incl. special-case presets);
  applying one reuses `POST /ai/programs/accept`. A guardrail test pins preset names to the
  seeded catalog.
- `ui/cardio/` — C25K/free-run screens + `CardioSchedule.kt` (shared next-run math for Home/
  Calendar/overview); the "active cardio program" flag is client-side DataStore, not server.
  `ManualCardioScreen` logs a walk/run after the fact (`CardioRepository.logManualSession`);
  distance is entered in the user's unit and converted to canonical meters at the edge
  (`ui/theme/AppLocals.kt` distance helpers). Completed cardio (manual, guided, or free) counts
  toward the Home streak + active-minutes stats alongside completed strength sessions
  (`HomeViewModel.loadStats`).
- `data/remote/SuiteAuthManager.kt` + `util/SuiteConfigReader` — suite SSO + hub config broker.
- `ui/theme/SpotterTheme.kt` — Pulse channel semantics (Spotter leads blue; effort/strength/
  streak/recovery channels). Components come from the Pulse library — never re-inline them.
- `widget/` — a home-screen **Glance** app widget (`SpotterWidget` + `WidgetContent`,
  `SpotterWidgetReceiver` in the manifest) showing today's workout / set progress; it reads a
  `data/local/WidgetSnapshotStore` snapshot (updated via `WidgetUpdater`) so it renders without a
  network round-trip.
- `util/ShortcutNav.kt` + `ui/navigation/ShortcutViewModel.kt` — static launcher shortcuts
  (`res/xml/shortcuts.xml`: Start workout / Log weight / Coach). Each fires a
  `spotter://shortcut/<target>` VIEW intent parked on a `ShortcutBus`; because the app gates on
  auth before the main graph, a shortcut is honoured *after* sign-in rather than dropped.
- `util/nudge/` — an opt-in workout-morning reminder (`WorkoutNudgeScheduler` enqueues a
  WorkManager `WorkoutNudgeWorker`); it re-checks enabled/permission/quiet-hours/is-today-a-
  workout-day at fire time so a stale schedule can't nag.

### Auth plumbing

`TokenRefreshAuthenticator` serializes refreshes behind a lock; only a 401/403 from
`/auth/refresh` signs out (network blips must not). AppAuth's redirect activity theme override in
the manifest is load-bearing (see CLAUDE.md suite section).

## Invariants (Spotter-specific; suite invariants in C:\Code\CLAUDE.md)

1. AI guardrail changes stay inside `app/services/ai/` and get called out explicitly in review.
2. Completed sets are immutable history — 409 on edit is a feature.
3. Clamp model output, reject client input — both against `app/limits.py`.
4. Timers derive from monotonic anchors; fonts are static per-weight instances (variable fonts
   render wrong on real devices).
5. Non-medical scope: presets/prompts advise professional clearance, never diagnose.

## Where to make common changes

- **New endpoint**: router (+ rate limit + auth dep) → service → schema; add a router test.
- **New screen**: `ui/<feature>/` + ViewModel + route in `AppNavGraph.kt`; Pulse components only.
- **Schema change**: Alembic revision (never hand-edit); if mirrored, Room entity + destructive
  rebuild is acceptable.
- **Prompt/guardrail change**: `services/ai/` only; add/extend a guardrail test with a mocked LLM.
