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
for the siblings), so its remaining 1.0 items were structural, not cosmetic. **All of Spotter's
solo 1.0 polish is now done (2026-07-15)**, and the Tier-W home-surface items (#7 — Glance widget
+ workout-morning nudge) shipped 2026-07-15/16. What genuinely remains toward 1.0 is the **on-device
pass** — interactive verification on real hardware (the emulator path needs a KVM host, so recent
UI work was verified by build + unit tests, not by driving the app). No feature gaps remain; the
1.0 declaration is passing the bar audit on-device.

1. ✓ **Offline-writes design** — DONE. Write-through + drain queue for bodyweight, routines, and
   programs; calendar serves last-known on offline read; body measurements (below) inherited it
   from day one. Debt #1.
2. ✓ **Rest countdown across process death** — DONE. `WorkoutTimerController` persists the rest
   end-anchor via `RestTimerStore` (DataStore) and restores it on init, so reopening mid-rest
   resumes exactly. Debt #3.
3. ✓ **Progression-engine presentation polish** — DONE. PR celebration moments (Summary
   `CelebrationPulse`/`ConfettiHost` + `newPrCount` pill, plus a per-set PR flag mid-workout) and
   the est-1RM trend chart done right (per-set Epley over time + a Weight / Est. 1RM toggle;
   Feature #4). Shipped 2026-07-15.

Already at versionName 1.1.2 — no bump gate needed; Spotter's 1.0 declaration is passing the
bar audit.

**Gap review 2026-07-14 (host ROADMAP3 additions — what a Hevy/Strong user would expect):**

4. ✓ **Supersets in the UI** — DONE. `WorkoutScreen` renders grouped exercises with a
   "SUPERSET A/B" header off `supersetGroup`.
5. ✓ **Ongoing-notification workout mode** — DONE (Sprint 8). One foreground-service notification
   per session carries the elapsed chronometer + the live "Resting · M:SS" line; tap → workout.
6. ✓ **Body measurements beyond weight** (neck/chest/waist/hips/arm/thigh) — SHIPPED 2026-07-15.
   Server migration `0011` + offline write-through; log dialog expander + a Measurements trend
   panel in the Body Weight tab.
7. ✓ **Today's-workout widget + workout-morning nudge** — SHIPPED 2026-07-15/16. A home-screen
   **Glance widget** (`widget/`, `SpotterWidgetReceiver`, 07-15) shows today's workout / set
   progress off a local `WidgetSnapshotStore` snapshot. The **morning nudge** (host Tier W2b,
   07-16) shipped as an
   opt-in *local* reminder (`util/nudge/`, WorkManager) that respects quiet hours and only fires on
   a workout day — no dependency on the suite push pipeline. Also shipped alongside: static
   **launcher shortcuts** (Start workout / Log weight / Coach, `util/ShortcutNav.kt`), routed
   through the auth gate so they resolve after sign-in.

## Debt to retire first

1. ✓ **Offline writes beyond workout mode** — DONE (2026-07). Write-through + drain queue applied
   to bodyweight metrics, routines, and programs; calendar serves last-known on offline read.
   (The "offline-finished workouts show no muscle-group breakdown" fix — needs `muscle_group`
   cached in Room — is still open as a small follow-up.)
2. **Persist the performed `ProgramDay` id on `WorkoutSession`** (migration) — kills the
   last-match heuristic in `get_next_day` for programs that repeat a routine.
3. ✓ **Rest countdown across process death** — DONE. `WorkoutTimerController` persists the rest
   end-anchor via `RestStore` and restores it on init (mirrors the `startedAtMs` elapsed anchor).
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
   (ducking) before any music integration. (✓ **Manual cardio entry** landed 2026-07-16 as a first
   slice — log a walk/run after the fact: activity type + duration + optional distance + date, a
   completed session that counts toward the Home streak + active-minutes like a guided run. Server
   `POST /cardio/sessions/manual` + `activity_type`/`distance_meters`, migration `0012`; client
   `ManualCardioScreen`. GPS/pace + coaching note still open.)
3. **Health Connect integration** — bodyweight in from a smart scale, workouts out to the
   Android health ecosystem. Standard API, high leverage, and it feeds Plate's targets too.
4. ✓ **Est-1RM trend** done right — SHIPPED 2026-07-15. `GET /progress/exercises/{id}` now returns
   `est_1rm` = the best per-set Epley of each day (not independent max(weight)/max(reps)); the
   Strength tab has a Weight / Est. 1RM chart toggle.

## Explicitly not worth it (my judgment — reverse deliberately if the user disagrees)

- Undo stack for AI adjustments — conversational revert ("put bench back in") has proven fine.
- Editing completed sessions — immutable history is a feature (409 stays).
- Social/leaderboards, Wear OS app, Play Store — out of character for this suite.
