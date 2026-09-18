"""Movement families — related lifts seed each other's starting load.

The problem this solves: a routine's ``target_weight`` is hand-authored per exercise (preset,
AI, or the user), so two lifts that train the same movement can sit at unrelated loads forever
— a Dumbbell Row prescribed at 10 lb next to a Barbell Row the user actually pulls at 100. The
progression engine (:mod:`app.progression`) is strictly per exercise, so nothing ever
reconciles them.

This module is the curated knowledge: which catalog exercises belong to the same **movement
family**, and roughly how their working loads relate. :func:`derive_weight` turns a user's
recent completed history on any family member into a starting load for a sibling.

Design rules (each pinned by ``tests/test_movement_families.py``):

* **Keyed by exercise name.** Catalog ids are generated per database; names are the stable key
  (presets already assume this). Every key must exist in the seed catalog.
* **Ratios, not equal loads.** A dumbbell row is per-hand at ~45 % of a barbell row's bar load.
  ``ratio`` is this lift's typical working load ÷ the family's reference lift (``ratio == 1.0``)
  at equal reps. Dumbbell entries are per-hand, matching the app's ``target_weight`` convention.
* **Rep normalisation.** Presets mix 5×5 and 3×10, so evidence is converted to an estimated
  1RM (:func:`app.progression.estimate_1rm`, reps capped at :data:`EVIDENCE_REPS_CAP`), scaled on
  that axis, then solved back for the target's rep prescription.
* **Source gating.** A ratio can't be conservative in both directions: a low ratio protects a
  *target* but inflates a *source* (a 400 lb leg press is not a 267 lb squat). Machine/cable
  stacks and shaky-ratio lifts are ``source=False`` — they can be seeded, never seed others.
  An exercise's own history always counts for itself (ratio identity).
* **Floor to a plate-friendly increment**, then clamp. Rounding down is the conservative side.
* **Bodyweight movements are never in a family** — there is no load to derive.

Pure functions of their inputs: the service loads rows and passes them in; no I/O.
"""

import datetime
import math
from collections.abc import Iterable
from dataclasses import dataclass

from app.limits import clamp_weight
from app.progression import estimate_1rm

# Epley drifts badly past ~12 reps; a 20-rep AMRAP is treated as a 12-rep set for estimation.
EVIDENCE_REPS_CAP = 12
_DEFAULT_TARGET_REPS = 8
_DUMBBELL_STEP = 5.0  # per-hand dumbbells come in 5 lb steps
_BAR_STEP = 2.5  # smallest common plate pair / stack pin


@dataclass(frozen=True)
class FamilyEntry:
    family: str
    ratio: float  # typical working load ÷ the family reference lift's, at equal reps
    source: bool  # may this lift's history seed its siblings?
    increment: float  # floor-rounding step for the derived load


@dataclass(frozen=True)
class Evidence:
    """One completed, non-warm-up working set (the service maps ORM rows into these)."""

    name: str
    date: datetime.date
    weight: float
    reps: int


def _e(family: str, ratio: float, source: bool = True, dumbbell: bool = False) -> FamilyEntry:
    return FamilyEntry(
        family=family,
        ratio=ratio,
        source=source,
        increment=_DUMBBELL_STEP if dumbbell else _BAR_STEP,
    )


HORIZONTAL_PUSH = "horizontal_push"
VERTICAL_PUSH = "vertical_push"
HORIZONTAL_PULL = "horizontal_pull"
VERTICAL_PULL = "vertical_pull"
HINGE = "hinge"
SQUAT = "squat"
CURL = "curl"
TRICEPS = "triceps"

# Ratios are typical relationships for a trained lifter, deliberately on the low side where the
# literature disagrees. Safety comes from the combination of: completed-only evidence, source
# gating, floor rounding, and the service's "never below the prescription" rule — not from any
# single number here.
FAMILIES: dict[str, FamilyEntry] = {
    # ── horizontal push (ref: Bench Press) ────────────────────────────────────────────────────
    "Bench Press": _e(HORIZONTAL_PUSH, 1.0),
    "Incline Bench Press": _e(HORIZONTAL_PUSH, 0.85),
    "Close-Grip Bench Press": _e(HORIZONTAL_PUSH, 0.9),
    "Decline Bench Press": _e(HORIZONTAL_PUSH, 1.05, source=False),  # rarely trained; inflates
    "Dumbbell Bench Press": _e(HORIZONTAL_PUSH, 0.4, dumbbell=True),
    "Dumbbell Incline Press": _e(HORIZONTAL_PUSH, 0.35, dumbbell=True),
    # ── vertical push (ref: Overhead Press) ───────────────────────────────────────────────────
    "Overhead Press": _e(VERTICAL_PUSH, 1.0),
    "Dumbbell Shoulder Press": _e(VERTICAL_PUSH, 0.4, dumbbell=True),
    "Arnold Press": _e(VERTICAL_PUSH, 0.35, source=False, dumbbell=True),
    # ── horizontal pull (ref: Barbell Row) ────────────────────────────────────────────────────
    "Barbell Row": _e(HORIZONTAL_PULL, 1.0),
    "T-Bar Row": _e(HORIZONTAL_PULL, 0.9),  # plate-loaded, comparable to the bar
    "Dumbbell Row": _e(HORIZONTAL_PULL, 0.45, dumbbell=True),
    "Chest-Supported Row": _e(HORIZONTAL_PULL, 0.4, dumbbell=True),
    "Seated Cable Row": _e(HORIZONTAL_PULL, 0.85, source=False),  # stack-dependent
    # ── vertical pull (ref: Lat Pulldown) ─────────────────────────────────────────────────────
    "Lat Pulldown": _e(VERTICAL_PULL, 1.0),
    "Straight-Arm Pulldown": _e(VERTICAL_PULL, 0.4, source=False),
    # ── hinge (ref: Conventional Deadlift) ────────────────────────────────────────────────────
    "Conventional Deadlift": _e(HINGE, 1.0),
    "Romanian Deadlift": _e(HINGE, 0.7),
    "Dumbbell Romanian Deadlift": _e(HINGE, 0.3, dumbbell=True),
    "Good Morning": _e(HINGE, 0.4, source=False),
    "Rack Pull": _e(HINGE, 1.1, source=False),  # supra-max partial; would inflate the pull
    "Hip Thrust": _e(HINGE, 0.9, source=False),  # trained lifters thrust more than they pull
    # ── squat (ref: Barbell Back Squat) ───────────────────────────────────────────────────────
    "Barbell Back Squat": _e(SQUAT, 1.0),
    "Barbell Front Squat": _e(SQUAT, 0.8),
    "Box Squat": _e(SQUAT, 0.9, source=False),
    "Hack Squat": _e(SQUAT, 0.8, source=False),
    "Leg Press": _e(SQUAT, 1.5, source=False),  # sled vs stack semantics unknown
    "Goblet Squat": _e(SQUAT, 0.3, source=False, dumbbell=True),  # one dumbbell, held
    "Bulgarian Split Squat": _e(SQUAT, 0.25, source=False, dumbbell=True),
    "Walking Lunge": _e(SQUAT, 0.2, source=False, dumbbell=True),
    "Dumbbell Reverse Lunge": _e(SQUAT, 0.2, source=False, dumbbell=True),
    "Step-Up": _e(SQUAT, 0.2, source=False, dumbbell=True),
    # ── curl (ref: Barbell Curl) ──────────────────────────────────────────────────────────────
    "Barbell Curl": _e(CURL, 1.0),
    "Dumbbell Curl": _e(CURL, 0.45, dumbbell=True),
    "Hammer Curl": _e(CURL, 0.45, dumbbell=True),
    "Incline Dumbbell Curl": _e(CURL, 0.35, source=False, dumbbell=True),
    "Concentration Curl": _e(CURL, 0.35, source=False, dumbbell=True),
    "Preacher Curl": _e(CURL, 0.8, source=False),
    "Cable Curl": _e(CURL, 0.9, source=False),
    # ── triceps (ref: Skull Crusher) ──────────────────────────────────────────────────────────
    "Skull Crusher": _e(TRICEPS, 1.0),
    "Tricep Pushdown": _e(TRICEPS, 1.0, source=False),
    "Overhead Cable Tricep Extension": _e(TRICEPS, 0.8, source=False),
    "Dumbbell Overhead Tricep Extension": _e(TRICEPS, 0.6, source=False, dumbbell=True),  # one DB
    "Tricep Kickback": _e(TRICEPS, 0.2, source=False, dumbbell=True),
}


def family_of(name: str) -> str | None:
    entry = FAMILIES.get(name)
    return entry.family if entry else None


def family_names(families: Iterable[str]) -> set[str]:
    """Every catalog name belonging to any of the given families."""
    wanted = set(families)
    return {name for name, entry in FAMILIES.items() if entry.family in wanted}


def _capped_reps(reps: int | None, default: int) -> int:
    return max(1, min(reps if reps is not None else default, EVIDENCE_REPS_CAP))


def derive_weight(
    target_name: str,
    target_reps: int | None,
    evidence: Iterable[Evidence],
) -> float | None:
    """The starting load for ``target_name`` implied by the user's recent history on its
    movement family, or ``None`` when nothing applies.

    For each family member that is a ``source`` (or is the target itself), only its **most
    recent** session counts; the best estimated 1RM of that session, divided by the member's
    ratio, puts it on the family's reference scale. The strongest reference wins, is scaled to
    the target's ratio, solved back for ``target_reps``, floored to the target's increment and
    clamped into bounds.
    """
    target = FAMILIES.get(target_name)
    if target is None:
        return None

    latest: dict[str, tuple[datetime.date, float]] = {}
    for ev in evidence:
        member = FAMILIES.get(ev.name)
        if member is None or member.family != target.family:
            continue
        if not member.source and ev.name != target_name:
            continue
        if ev.weight is None or ev.weight <= 0:
            continue
        e1rm = estimate_1rm(ev.weight, _capped_reps(ev.reps, 1))
        prev = latest.get(ev.name)
        if prev is None or ev.date > prev[0]:
            latest[ev.name] = (ev.date, e1rm)
        elif ev.date == prev[0] and e1rm > prev[1]:
            latest[ev.name] = (ev.date, e1rm)

    if not latest:
        return None

    reference = max(e1rm / FAMILIES[name].ratio for name, (_, e1rm) in latest.items())
    reps = _capped_reps(target_reps, _DEFAULT_TARGET_REPS)
    target_e1rm = reference * target.ratio
    raw = target_e1rm if reps <= 1 else target_e1rm / (1 + reps / 30.0)
    # The epsilon keeps an exact identity (own history at the same reps) from landing one float
    # ulp short of the increment boundary and flooring a whole step down.
    floored = math.floor(raw / target.increment + 1e-9) * target.increment
    if floored < target.increment:
        return None
    return clamp_weight(floored)
