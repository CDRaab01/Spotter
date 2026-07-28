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
| Sessions + sets | `sessions.py` | `session_service` (+ `progression.py` for `/prior-bests`) | `WorkoutSession`, `SetLog` (+ `rpe`, `set_type`) |
| Cardio | `cardio.py` | `cardio_service` | `CardioSession` (guided/free live runs **+** `POST /cardio/sessions/manual` after-the-fact walk/run entries: `program_id="manual"`, completed, optional `activity_type`/`distance_meters`) |
| Metrics/progress/calendar | `metrics.py`, `progress.py`, `calendar.py` | matching services | `BodyMetric` + reads over sessions |
| Exercise catalog | `exercises.py` | — (seeded reads) | `Exercise` (name, muscle group, equipment, **`instructions` + `secondary_muscles`** backfilled for all 81 seeded movements by migration `0013`; `GET /exercises/{id}` serves the detail screen) |
| AI | `ai.py` | `services/ai/` (see below) | writes via other services only |
| Insights | `insights.py` | `insights_service` | reads sessions/sets — stalled lifts + PRs this week, **reusing `progression.stalled_sessions()`** rather than a second stall implementation |
| Cross-app | `workouts.py` | `workout_service` | reads sessions/cardio |
| Export | `export.py` | `export_service` | generic `__table__.columns` dump (`GET /export`) + a flat per-set CSV (`GET /export/sets.csv`) |

**Naming trap:** the original "plan" concept was renamed to **routine** (`/routines`,
`WorkoutRoutine`). The AI schemas still say `SuggestedPlan` — that's the AI-draft object, not a
DB row, so the name is intentional. (Historical changelog entries in CLAUDE.md still reference the
old `/plans` path and the `plan_id` column by design — they describe past fixes.)

### The AI module (`app/services/ai/`) — the part you must not casually edit

All prompt + guardrail logic is deliberately confined here so it can be audited in isolation:
- `prompts.py` — the server-side system prompt (intake protocol, plan/program JSON rules,
  live-adjustment rules, scope limits). Client never supplies prompts.
- `client.py` — LM Studio transport + extraction: `_extract_plan` / `_extract_program` /
  `_extract_adjustment` / `_extract_profile_update`. **Block selection is by shape, not
  position** (`_first_valid_block`): a reply may legitimately carry two blocks (a workout
  suggestion and a profile update), and the extractors used to validate only the *first* one, so
  a model that emitted them in the other order silently dropped the workout suggestion. Prompt
  ordering is guidance, not a guarantee — pinned by `test_block_order_does_not_matter`. They
  resolve exercise names → catalog ids, and
  **clamp** out-of-range numbers into `app/limits.py` bounds (clamp, never drop). Malformed
  responses → 502; unreachable LM Studio → 503. Injection screening runs over *every* user turn:
  the NEW turn hard-fails (422), while a blocked turn in the resent *history* is silently dropped
  before it reaches the model — rejecting on history permanently poisoned the conversation
  (clients resend the full transcript, so one blocked phrase 422'd every later request).
- `context_service.py` — trusted context from the DB: the user's persisted **training profile**
  (equipment/experience/goal/age group/limitations, from `users`) then training history, plus a
  live-session summary when `current_session_id` is given. Client-supplied profile text is
  appended as stated preferences only — it never overrides DB-derived facts.
  **Equipment belongs in the trusted block, not the client string.** It used to live only in
  Android DataStore, written once by onboarding (which most users never see, since login marks
  onboarding done) and forwarded as an optional `user_context` string — so the coach genuinely
  did not know what equipment existed and re-asked every conversation. Anything the coach must
  never forget belongs on the user row and in this function.
- `adjustment_apply.py` — the only path an AI suggestion touches the DB, and only via
  `POST /ai/sessions/{id}/adjust` after an explicit user Apply. **Completed sets are immutable**;
  only incomplete sets are ever mutated, in one transaction.
- `debrief.py` / `recap.py` — read-only narration (`POST /ai/sessions/{id}/debrief`,
  `GET /ai/recap/weekly`). Neither can write. **The recap's numbers are always computed
  server-side and the narrative is best-effort**: LM Studio unreachable ⇒ `narrative: null` and
  the endpoint still 200s, so a dead LLM degrades the prose, never the data (the Dragonfly
  digest philosophy). All three LM callers share `client.lm_completion()`, so the 502/503/504
  mapping is identical everywhere.

The trust model, everywhere: **AI proposes, user commits.** There is no autonomous write path;
adding one is an architecture change, not a feature. The coach may propose a **training-profile
update** ("I bought a squat rack") the same way — a suggestion card that applies through the
ordinary `PATCH /users/me/profile`, never a silent write. It is the one suggestion that sits
*outside* the single-suggestion rule: the invariant is **exactly one workout suggestion
(adjustment > program > plan), plus an optional profile update**, because learning a durable fact
and acting on it are different things and forcing one would make the user re-ask for the program
they requested in the same breath. The workout screen's progression **Apply**
button (2026-07-28) rides the same `POST /ai/sessions/{id}/adjust` rails with a client-built
`adjust_weight` action — user-initiated, same incomplete-sets-only invariant, and its
`apply_to_routine` write-back is what advances the routine's `target_weight` so the next
session pre-fills at the new load.

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
- **Training profile** (`ProfileRepository`): the server row is the source of truth and
  `AppPreferences.userProfile` is the offline mirror that existing callers already read. Two
  ordering rules matter — `refresh()` **drains pending edits before pulling** (otherwise a sync
  round would pull the stale server copy straight over an edit made offline), and `save()`
  returns whether the server actually acknowledged, so the UI can say "saved on this device,
  will sync later" rather than falsely claiming a sync.
- **Exercise catalog mirror** (`ExerciseEntity`/`ExerciseDao`, Room v13): the seeded server
  catalog is mirrored locally — seeded opportunistically by the Home sync round and the reconnect
  observer, and refreshed as a side effect of every online read (`ExerciseRepository`). Offline it
  backs Exercise Library search (LIKE on name), preset-program name→id resolution
  (`ProgramPresetsViewModel` → `listAll()`), and the offline muscle-group summary below. The
  degrade rule everywhere: **`IOException` falls back to the mirror; `retrofit2.HttpException`
  keeps erroring** — the server answered, so cached rows must not mask its error.
- **Offline muscle-group breakdown** (closes the old "offline-finished workouts show no
  muscle-group breakdown" gap — via the catalog mirror, not the once-planned
  routine-payload/Room-column approach): when a session read/finish serves from Room,
  `SessionRepository.fallbackToLocal` joins the set logs to `ExerciseDao.getByIds` and
  `data/repository/OfflineMuscleGroups.kt` (pure, table-tested) reproduces the server's
  aggregation exactly — completed sets only, volume in **kg** (`reps × lb × 0.453592`, null/zero
  weight adds sets but no volume), one decimal, alphabetical groups. Exercises the mirror doesn't
  know drop out (degrades to the old empty state).
- **Stale banners (offline honesty):** `AppPreferences.lastSuccessfulSyncMs` is stamped whenever
  a sync round reaches the server (Home's routine-pull probe; the reconnect observer's catalog
  refresh). When Home's sync probe hits an `IOException`, or History's listing came from the
  mirror (`SessionRepository.listSessionsWithFreshness().fromCache`), the screen renders Pulse's
  `StaleBanner` (streak/amber channel) dated by that timestamp; an `HttpException` never shows
  the banner — it keeps erroring through the normal paths.

Room is a server mirror: destructive migration is acceptable and configured. (Remaining offline
gap, accepted: `getPriorBests` returns empty offline — prior-best/progression hints are
server-computed and simply absent until reconnect.)

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

- `ui/workout/` — the core product surface (set logging, rest panel, resume strip via
  `util/ActiveWorkoutStore`). Supersets render as visually paired rows under a "SUPERSET A/B"
  header driven by the routine's `supersetGroup` (shared rest); the server derives the grouping,
  the client only displays it. Sets carry a **type** (tap the set number: normal/warm-up/drop/
  failure/AMRAP, badge replaces the number) and an optional **RPE** entry (Settings → Workout).
  Rest supports ±15s, an auto-start toggle, and per-exercise overrides read from the routine
  mirror (`SessionRepository.getRestSeconds`) — an explicit prescription is used verbatim, with
  no failure bump. Exercises can be added/removed mid-session by hand, not just by the coach.
- `ui/ai/` — coach chat + the three suggestion cards (plan / program / live adjustment).
- `ui/history/` — the history list plus `SessionDetailScreen` (2026-07-28): a read-only
  per-set breakdown of a past session served by `SessionRepository.getSession`, so it works
  offline from the Room mirror. History cards navigate (resume when in-progress, detail when
  done) per the "cards navigate, buttons act" grammar — the old tap-to-expand preview is gone.
- `ui/program/ProgramPresets.kt` — client-side preset programs (incl. special-case presets);
  applying one reuses `POST /ai/programs/accept`, with a preview screen
  (`ProgramPresetDetailScreen`) offering **add-and-activate or add-without-activating**. A
  guardrail test pins preset names to the seeded catalog.
  **Presets are cycles, not calendar weeks:** each defines the smallest repeating unit
  (`A, Rest, B, Rest`), because `accept_program` creates one routine per training day — a literal
  7-day week listing the same day twice would create duplicate routines. `presetCadenceLine`
  derives the advertised frequency from the day list, so the description can't drift from the
  schedule again. A training day whose exercises all fail to resolve is **dropped**, never sent
  empty — an empty day now *means* rest.
- `ui/program/Periodization.kt` — pure `programWeek`/`weekLabel` over the mirrored `startedOn`.
  The server's `current_week`/`is_deload_week` are deliberately **not** mirrored (they're
  time-derived, so a cached copy would be stale by definition); the client recomputes them from
  the same formula instead.
- `ui/history/SessionTemplate.kt` — pure derivation for "repeat this workout" / "save as routine"
  (a completed session → `RoutineExerciseIn`s), table-tested.
- `ui/exercise/ExerciseDetailScreen.kt` — form instructions + muscles from the catalog mirror
  (offline-capable), plus a server-computed history chart and PR panel loaded independently, so
  a history failure never takes the instructions down with it.
- `ui/recap/` + `ui/summary/` — the weekly recap screen and the post-workout coach debrief. Both
  treat the LLM as optional: the debrief card is **omitted entirely** on failure (LM Studio being
  down is the normal case here, not an error worth showing), and the recap renders its
  server-computed numbers with a quiet note when the narrative is null.
- `ui/cardio/` — C25K/free-run screens + `CardioSchedule.kt` (shared next-run math for Home/
  Calendar/overview); the "active cardio program" flag is client-side DataStore, not server.
  `ManualCardioScreen` logs a walk/run after the fact (`CardioRepository.logManualSession`);
  distance is entered in the user's unit and converted to canonical meters at the edge
  (`ui/theme/AppLocals.kt` distance helpers). Completed cardio (manual, guided, or free) counts
  toward the Home streak + active-minutes stats alongside completed strength sessions
  (`HomeViewModel.loadStats`), and lists back on Cardio home's "Recent activity" panel
  (`CardioHomeViewModel`, 2026-07-28).
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
- `data/export/` — Settings → Export data. Streams `GET /export` (JSON) and `GET /export/sets.csv`
  into `cacheDir/exports/` and hands the file to the share sheet via FileProvider. Filename
  parsing (`ExportFilenames`) is pure and table-tested, including RFC 5987 `filename*` and
  path-traversal sanitising.
- `health/` — **write-only** Health Connect mirroring, opt-in and default off. `HealthMapper` is
  pure and SDK-free (it emits a local enum + pounds, not `EXERCISE_TYPE_*`/`Mass`, so the
  time/type/unit logic is plain-JUnit testable); `HealthConnectManager` wraps availability,
  permissions and writes, every path `runCatching`-guarded. Repos call a one-line
  `HealthSync` hook that defaults to `HealthSync.NoOp` in the constructor — Dagger injects the
  real binding while direct construction in tests stays unchanged. The
  "only on entering completed" transition guard lives in `HealthConnectSync`, so re-saving a
  finished workout can't duplicate a record, and **a Health Connect failure never affects the
  user's save**.

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
5. Non-medical scope: presets/prompts advise professional clearance, never diagnose. The
   pregnancy/postpartum section in `prompts.py` is the sharpest case — it redirects symptoms to a
   doctor or pelvic floor physiotherapist and never estimates healing timelines or judges
   readiness. Extending it means adding *exercise selection and referrals*, never clinical
   judgement. Guarded by `tests/test_ai_postpartum.py` and the preset contraindication test.
6. **Warm-up sets are not training data.** `set_type == "warmup"` is excluded from volume,
   progression inputs, est-1RM trend and PR detection — that exclusion is the entire point of
   the set-type concept, so any new computation over sets must filter it too.
7. **A PR requires a prior best to beat.** A brand-new exercise's first session sets a baseline,
   not a PR — otherwise every first workout reads as a wall of records.

### Periodization (as built)

A program may carry `weeks` + `deload_week`; activation stamps `started_on`. `current_week` is
derived — `((today - started_on).days // 7) % weeks + 1`, so mesocycles **cycle** rather than
running off the end — and `is_deload_week` compares it to `deload_week`. Both live in
`program_service` as pure helpers shared by the create and read paths, so a session seeded as a
deload and a screen labelled "deload week" can never disagree. A deload session seeds
`ceil(sets × DELOAD_SET_FACTOR)` sets at `weight × DELOAD_WEIGHT_FACTOR`. The coach can author
`weeks`/`deload_week` through the normal extract-and-clamp path — it gains no new write power,
just a richer suggestion shape.

## Where to make common changes

- **New endpoint**: router (+ rate limit + auth dep) → service → schema; add a router test.
- **New screen**: `ui/<feature>/` + ViewModel + route in `AppNavGraph.kt`; Pulse components only.
- **Schema change**: Alembic revision (never hand-edit); if mirrored, Room entity + destructive
  rebuild is acceptable.
- **Prompt/guardrail change**: `services/ai/` only; add/extend a guardrail test with a mocked LLM.
