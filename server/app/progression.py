"""Progressive-overload engine (pure, table-tested — ROADMAP2 T3 #1).

The next-set suggestion, upgraded from a one-shot linear bump to real progression on data Spotter
already has (logged sets + the routine's rep target). Three ideas:

* **Double progression** — earn the reps before the load. Only when every working set hits the
  routine's ``target_reps`` do we add weight; short of that, hold the weight and push reps.
* **Deload on stall** — after :data:`DELOAD_STALL_SESSIONS` consecutive sessions stuck at the same
  weight without meeting the rep goal, cut the load :data:`DELOAD_PCT` and build back. This breaks
  the "repeat this weight forever" trap that kills linear progression.
* **e1RM + PR** — surface the best-set estimated 1RM (Epley) and flag a new all-time best.

Pure functions of their inputs (the service loads sets/history and passes them in); no I/O, no
schema coupling — the service maps ORM rows into the small dataclasses below. Weight suggestions are
clamped to :func:`app.limits.clamp_weight`. With no ``target_reps`` (a routine-less session) it
degrades to the old "all sets completed → add weight" linear behaviour, so nothing regresses.
"""

import datetime
from dataclasses import dataclass

from app.limits import clamp_weight

# Bigger, stronger muscles take larger jumps (kept identical to the old inline set).
LOWER_BODY_GROUPS = {"legs", "quads", "hamstrings", "glutes", "calves", "back"}
LOWER_STEP_LB = 5.0
UPPER_STEP_LB = 2.5

# After this many consecutive stalled sessions at ~the same weight, deload by DELOAD_PCT.
DELOAD_STALL_SESSIONS = 3
DELOAD_PCT = 0.10

# Actions the client renders differently.
ADD_WEIGHT = "add_weight"
ADD_REPS = "add_reps"
HOLD = "hold"
DELOAD = "deload"
BODYWEIGHT = "bodyweight"


def estimate_1rm(weight: float, reps: int) -> float:
    """Estimated one-rep max (Epley). A single rep is already a 1RM, so it returns the weight
    unchanged; otherwise ``weight * (1 + reps / 30)``. Mirrors the client's
    ``util/FitnessFormulas.kt`` (moved here from ``progress_service`` so there's one definition)."""
    return weight if reps <= 1 else weight * (1 + reps / 30.0)


@dataclass(frozen=True)
class SetResult:
    """One logged working set the engine reasons over."""

    reps: int
    weight: float | None
    completed: bool


@dataclass(frozen=True)
class SessionHistory:
    """A prior session's working sets for one exercise (pass the list most-recent-first)."""

    date: datetime.date
    sets: list[SetResult]


@dataclass(frozen=True)
class ProgressionSuggestion:
    action: str  # add_weight | add_reps | hold | deload | bodyweight
    suggested_weight: float | None
    suggested_reps: int | None
    reason: str
    e1rm: float | None
    is_pr: bool


def _step_for(muscle_group: str | None) -> float:
    return LOWER_STEP_LB if (muscle_group or "").lower() in LOWER_BODY_GROUPS else UPPER_STEP_LB


def _best_e1rm(sets: list[SetResult]) -> float | None:
    best: float | None = None
    for s in sets:
        if s.weight is None:
            continue
        e = estimate_1rm(s.weight, s.reps)
        if best is None or e > best:
            best = e
    return best


def _working_weight(sets: list[SetResult]) -> float | None:
    weights = [s.weight for s in sets if s.weight is not None]
    return max(weights) if weights else None


def _met_goal(sets: list[SetResult], target_reps: int | None) -> bool:
    """A session 'succeeded' when every set was completed and (if a rep target exists) hit it."""
    if not sets:
        return False
    if not all(s.completed for s in sets):
        return False
    return target_reps is None or all(s.reps >= target_reps for s in sets)


def _count_stalls(
    last_weight: float,
    target_reps: int | None,
    exercise_history: list[SessionHistory],
) -> int:
    """Consecutive stalled sessions ending in the current one (which is already a miss = 1),
    walking back through ``exercise_history`` (most-recent-first) while the working weight matches
    and the rep goal wasn't met."""
    stalls = 1  # the current session is the miss that got us here
    for h in exercise_history:
        w = _working_weight(h.sets)
        if w is None or abs(w - last_weight) > 0.01:
            break  # a different working weight ends the streak at this load
        if _met_goal(h.sets, target_reps):
            break  # a prior success ends the streak
        stalls += 1
    return stalls


def suggest_progression(
    target_reps: int | None,
    last_sets: list[SetResult],
    exercise_history: list[SessionHistory],
    muscle_group: str | None,
    is_bodyweight: bool,
) -> ProgressionSuggestion:
    """Suggest the next session's load/reps for one exercise from the most recent session
    (``last_sets``) and the sessions before it (``exercise_history``, most-recent-first, excluding
    ``last_sets``)."""
    e1rm = _best_e1rm(last_sets)
    prior_best = None
    for h in exercise_history:
        hb = _best_e1rm(h.sets)
        if hb is not None and (prior_best is None or hb > prior_best):
            prior_best = hb
    is_pr = e1rm is not None and (prior_best is None or e1rm > prior_best)

    # Bodyweight (or a set list with no loads) → add reps, never load.
    if is_bodyweight or not last_sets or all(s.weight is None for s in last_sets):
        return ProgressionSuggestion(
            action=BODYWEIGHT if is_bodyweight else ADD_REPS,
            suggested_weight=None,
            suggested_reps=target_reps,
            reason="Bodyweight — add reps before adding load."
            if is_bodyweight
            else "Add reps to keep progressing.",
            e1rm=e1rm,
            is_pr=is_pr,
        )

    last_weight = _working_weight(last_sets)
    all_completed = all(s.completed for s in last_sets)
    reps_met = target_reps is None or all(s.reps >= target_reps for s in last_sets)

    # Graduated: everything completed at the rep goal → add load.
    if all_completed and reps_met:
        step = _step_for(muscle_group)
        suggested = clamp_weight(last_weight + step)
        if suggested is not None and suggested <= last_weight:
            return ProgressionSuggestion(
                HOLD,
                last_weight,
                target_reps,
                "At the weight limit — hold and add reps.",
                e1rm,
                is_pr,
            )
        reason = (
            f"All sets at {target_reps}+ reps — add {step:g} lb."
            if target_reps is not None
            else f"Completed all sets — add {step:g} lb."
        )
        return ProgressionSuggestion(ADD_WEIGHT, suggested, target_reps, reason, e1rm, is_pr)

    # Completed, but short of the rep goal → hold the weight and chase reps.
    if all_completed and not reps_met:
        got = min(s.reps for s in last_sets)
        return ProgressionSuggestion(
            ADD_REPS,
            last_weight,
            target_reps,
            f"Got {got}/{target_reps} reps — hit {target_reps} across all sets to add load.",
            e1rm,
            is_pr,
        )

    # A real miss (a set left incomplete) → hold, or deload once the stall is entrenched.
    stalls = _count_stalls(last_weight, target_reps, exercise_history)
    if stalls >= DELOAD_STALL_SESSIONS:
        deloaded = clamp_weight(last_weight * (1.0 - DELOAD_PCT))
        return ProgressionSuggestion(
            DELOAD,
            deloaded,
            target_reps,
            f"Stalled {stalls} sessions — deload to {deloaded:g} lb and build back stronger.",
            e1rm,
            is_pr,
        )
    return ProgressionSuggestion(
        HOLD,
        last_weight,
        target_reps,
        "Missed reps last time — repeat this weight and complete all sets first.",
        e1rm,
        is_pr,
    )
