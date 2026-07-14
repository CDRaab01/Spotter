# ROADMAP.md — Spotter (departing-engineer assessment, 2026-07-03)

Grounded in the deferred-backlog entries scattered through CLAUDE.md's sprint/audit logs,
consolidated and prioritized. Suite-wide items (backups, Pulse migration, SSO 2e) live in the
host-level roadmap; this is Spotter-specific.

## Road to 1.0 (suite pivot, 2026-07-13)

The suite entered its **1.0 polish round** (host-level ROADMAP3, C:\Code): every app must pass a
shared bar — onboarding, designed empty/loading/error states, motion/celebration + dark/light
parity, defined offline behavior, no dead settings, an on-device pass, gating screenshot
baselines, icon quality, truthful docs. **Spotter is the suite's polish reference** (`States.kt`,
`ui/onboarding/`, `Motion.kt`, confetti — Tier P of the host roadmap promotes these into Pulse
for the siblings), so its remaining 1.0 items are structural, not cosmetic:

1. **Offline-writes design** — Debt #1 below; the one place daily use still feels unfinished.
2. **Rest countdown across process death** — Debt #3.
3. **Progression-engine presentation polish** — the engine shipped (see below); give it the
   premium frame: PR celebration moments via the Pulse celebration primitives, and the
   est-1RM trend chart done right (Feature #4).

Already at versionName 1.1.2 — no bump gate needed; Spotter's 1.0 declaration is passing the
bar audit.

**Gap review 2026-07-14 (host ROADMAP3 additions — what a Hevy/Strong user would expect):**

4. **Supersets in the UI.** The data model already carries `supersetGroup`
   (`RoutineExerciseEntity`) — surface it: visually paired exercises in WorkoutScreen,
   alternating set entry, shared rest timer. Scope alongside the `RoutineExercise` composite-PK
   migration (Debt #4) since both touch routine-exercise identity.
5. **Ongoing-notification workout mode** — the rest countdown as a live chronometer notification
   on the lock screen (foreground service during an active session; tap → back to the workout).
   *The* premium feel for a lifting app, and it pairs with Debt #3 (rest state across process
   death) — one design solves both.
6. **Body measurements beyond weight** (arms/waist/chest/thighs) — standard in every competitor;
   design the tables/UI so the offline-writes work (Road-to-1.0 #1) covers them from day one.
7. **Today's-workout / rest-timer widget** (host Tier W4 Pulse widget family) and a workout-day
   morning nudge via the suite push pipeline (host Tier W2b, opt-in).

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

## Cross-app work (approved 2026-07-03 — see Dragonfly/CROSS-APP.md for the full design)

- **Bodyweight write-through to Plate** (CROSS-APP item 1): Plate becomes the weight
  authority; Spotter's bodyweight logging writes through to Plate's `/cross-app/weight`
  (kg on the wire), keeps `BodyMetric` as the offline cache, and charts read the merged
  series. Flagged on `PLATE_BASE_URL` + secret.
- ✓ **Range form of `/workouts`** — SHIPPED 2026-07-11 (federated-awareness Link B; also consumed
  by Magpie's cost-per-visit, Link G, 2026-07-12). Additive `?start=&end=` alongside the
  single-date form; contract fixture committed.

## Feature work (in value order)

1. ✓ **Progressive overload engine — SHIPPED 2026-07-04** (ROADMAP2 T3 #1): `app/progression.py`
   double progression on `target_reps` (`add_weight`/`add_reps`), stall → `deload` after 3 stuck
   sessions, best-set e1RM + PR flag, surfaced in WorkoutScreen (same suggest-never-auto-write
   trust model). Remaining work is presentation (Road to 1.0 #3 above), not the engine.
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
