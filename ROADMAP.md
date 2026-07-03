# ROADMAP.md — Spotter (departing-engineer assessment, 2026-07-03)

Grounded in the deferred-backlog entries scattered through CLAUDE.md's sprint/audit logs,
consolidated and prioritized. Suite-wide items (backups, Pulse migration, SSO 2e) live in the
host-level roadmap; this is Spotter-specific.

## Debt to retire first

1. **Offline writes beyond workout mode** — the standing [MED] backlog item and the app's
   biggest architectural gap. Metrics, routines, programs, and calendar all throw offline while
   workout logging syncs beautifully. Design once (write-through + sync queue, reusing the
   workout-mode reconciliation patterns), then apply per repository. Do the
   "offline-finished workouts show no muscle-group breakdown" fix in the same pass (needs
   `muscle_group` cached in Room).
2. **Persist the performed `ProgramDay` id on `WorkoutSession`** (migration) — kills the
   last-match heuristic in `get_next_day` for programs that repeat a routine.
3. **Rest countdown across process death** — the anchor persistence pattern already exists for
   the elapsed clock (`startedAtMs`); extend it to `WorkoutTimerController`'s rest state.
4. **`RoutineExercise` composite PK** forbids the same exercise twice in a routine (e.g.
   heavy/light squat in one day). Room migration + server-aligned identity; known, deferred,
   still worth doing before someone hits it.
5. **Deploy operability niceties:** dump `docker compose logs` on health-gate failure;
   configurable health timeout. Small, listed since June, keeps biting during incident triage.

## Feature work (in value order)

1. **Progressive overload engine.** The data is all there (`prior-bests` already computes
   `suggested_weight`) — close the loop: after N successful sessions at a load, propose the
   increment as an Apply card (same trust model as AI adjustments: suggest, never auto-write);
   detect stalls and propose a deload week. This is the single feature that turns a workout
   *logger* into a *coach*, and it needs no new AI surface.
2. **Cardio phase 2:** GPS distance/pace for outdoor runs, and the deliberately-stubbed
   **AI post-run coaching note** (sessions are already stored for it). Audio cues over music
   (ducking) before any music integration.
3. **Health Connect integration** — bodyweight in from a smart scale, workouts out to the
   Android health ecosystem. Standard API, high leverage, and it feeds Plate's targets too.
4. **Est-1RM trend** done right (per-set epley over time, not independent max(weight)/max(reps))
   — the [LOW] backlog item; do it when touching progress charts for #1.

## Explicitly not worth it (my judgment — reverse deliberately if the user disagrees)

- Undo stack for AI adjustments — conversational revert ("put bench back in") has proven fine.
- Editing completed sessions — immutable history is a feature (409 stays).
- Social/leaderboards, Wear OS app, Play Store — out of character for this suite.
