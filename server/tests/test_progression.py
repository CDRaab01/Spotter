"""Pure progressive-overload engine tests (ROADMAP2 T3 #1). Table-driven, no DB.

Covers double progression (add_weight when reps met / add_reps when short), the stall→deload ladder
at the exact threshold, the weight-limit hold, bodyweight, e1RM + PR detection, the no-target linear
fallback, and stall-streak breaks (weight change / a prior success).
"""

import datetime

import pytest

from app.loading import DEFAULT_INVENTORY_LB, Inventory, Plate, ladder_for
from app.progression import (
    ADD_REPS,
    ADD_WEIGHT,
    BODYWEIGHT,
    DELOAD,
    HOLD,
    SessionHistory,
    SetResult,
    estimate_1rm,
    suggest_progression,
)

D0 = datetime.date(2026, 6, 1)


def _sr(reps, weight, completed=True):
    return SetResult(reps=reps, weight=weight, completed=completed)


def _hist(offset, sets):
    return SessionHistory(date=D0 + datetime.timedelta(days=offset), sets=sets)


# ── e1RM ──────────────────────────────────────────────────────────────────────


def test_estimate_1rm():
    assert estimate_1rm(100.0, 1) == 100.0
    assert estimate_1rm(100.0, 5) == pytest.approx(100.0 * (1 + 5 / 30))


# ── double progression ────────────────────────────────────────────────────────


def test_add_weight_upper_when_reps_met():
    r = suggest_progression(5, [_sr(5, 100.0), _sr(5, 100.0), _sr(5, 100.0)], [], "chest", False)
    assert r.action == ADD_WEIGHT
    assert r.suggested_weight == pytest.approx(102.5)  # +2.5 upper
    assert "add 2.5 lb" in r.reason
    assert r.e1rm == pytest.approx(estimate_1rm(100.0, 5))


def test_add_weight_lower_gets_bigger_jump():
    r = suggest_progression(5, [_sr(5, 100.0), _sr(5, 100.0)], [], "legs", False)
    assert r.action == ADD_WEIGHT
    assert r.suggested_weight == pytest.approx(105.0)  # +5 lower body


def test_add_reps_when_completed_but_short_of_target():
    r = suggest_progression(5, [_sr(3, 100.0), _sr(3, 100.0), _sr(4, 100.0)], [], "chest", False)
    assert r.action == ADD_REPS
    assert r.suggested_weight == pytest.approx(100.0)  # hold the weight
    assert "3/5" in r.reason  # min reps got / target


def test_no_target_linear_fallback_adds_weight():
    r = suggest_progression(None, [_sr(8, 100.0), _sr(8, 100.0)], [], "chest", False)
    assert r.action == ADD_WEIGHT
    assert "Completed all sets" in r.reason


def test_weight_limit_holds():
    # 600 is the WEIGHT_BOUNDS_LB ceiling; +step clamps back to <= current → hold and add reps.
    r = suggest_progression(5, [_sr(5, 600.0), _sr(5, 600.0)], [], "legs", False)
    assert r.action == HOLD
    assert "weight limit" in r.reason


# ── stall / deload ladder ─────────────────────────────────────────────────────


def _miss():
    return [_sr(5, 100.0), _sr(5, 100.0), _sr(2, 100.0, completed=False)]


def test_missed_holds_when_not_yet_stalled():
    # current miss = 1 stall (< 3) → hold, not deload.
    r = suggest_progression(5, _miss(), [], "chest", False)
    assert r.action == HOLD
    assert r.suggested_weight == pytest.approx(100.0)


def test_two_stalls_still_holds():
    r = suggest_progression(5, _miss(), [_hist(-3, _miss())], "chest", False)
    assert r.action == HOLD


def test_three_stalls_deloads():
    r = suggest_progression(5, _miss(), [_hist(-3, _miss()), _hist(-6, _miss())], "chest", False)
    assert r.action == DELOAD
    assert r.suggested_weight == pytest.approx(90.0)  # 100 * (1 - 0.10)
    assert "Stalled 3 sessions" in r.reason


def test_stall_streak_breaks_on_weight_change():
    # A prior miss at a *different* weight ends the streak → only 2 stalls at 100 → hold.
    r = suggest_progression(
        5, _miss(), [_hist(-3, _miss()), _hist(-6, [_sr(5, 95.0, completed=False)])], "chest", False
    )
    assert r.action == HOLD


def test_stall_streak_breaks_on_prior_success():
    success = [_sr(5, 100.0), _sr(5, 100.0), _sr(5, 100.0)]
    r = suggest_progression(5, _miss(), [_hist(-3, success), _hist(-6, _miss())], "chest", False)
    assert r.action == HOLD  # the success at index 0 breaks the streak → 1 stall


# ── bodyweight ────────────────────────────────────────────────────────────────


def test_bodyweight_adds_reps_no_load():
    r = suggest_progression(10, [_sr(8, None), _sr(8, None)], [], "back", True)
    assert r.action == BODYWEIGHT
    assert r.suggested_weight is None
    assert "add reps" in r.reason.lower()


def test_all_none_weights_treated_as_add_reps():
    r = suggest_progression(10, [_sr(8, None)], [], "back", False)
    assert r.action == ADD_REPS
    assert r.suggested_weight is None


# ── PR detection ──────────────────────────────────────────────────────────────


def test_is_pr_true_when_beating_history():
    r = suggest_progression(5, [_sr(5, 100.0)], [_hist(-3, [_sr(5, 90.0)])], "chest", False)
    assert r.is_pr is True


def test_is_pr_false_when_history_higher():
    r = suggest_progression(5, [_sr(5, 100.0)], [_hist(-3, [_sr(3, 120.0)])], "chest", False)
    # e1RM(120,3)=132 > e1RM(100,5)=116.7 → not a PR
    assert r.is_pr is False


# ── loadable jumps (app/loading.py ladders) ──────────────────────────────────
# The reported bug: "+2 lb" suggestions. The engine's +2.5 upper-body step is 1.25 lb a side on a
# barbell. With the user's equipment ladder every suggested load is one they can actually make.

BENCH = ladder_for("barbell", "Bench Press", DEFAULT_INVENTORY_LB)
_THREE_AT_8 = [_sr(8, 115.0), _sr(8, 115.0), _sr(8, 115.0)]


def test_barbell_increase_is_a_plate_on_each_side():
    r = suggest_progression(8, _THREE_AT_8, [], "chest", False, ladder=BENCH)
    assert r.action == ADD_WEIGHT
    assert r.suggested_weight == 120.0  # not 117.5
    assert "add 5 lb (2.5 lb per side)" in r.reason


def test_lower_body_step_already_loadable_is_unchanged():
    squat = ladder_for("barbell", "Barbell Back Squat", DEFAULT_INVENTORY_LB)
    sets = [_sr(5, 135.0)] * 3
    r = suggest_progression(5, sets, [], "legs", False, ladder=squat)
    assert r.suggested_weight == 140.0


def test_dumbbells_move_to_the_next_pair():
    inv = Inventory(unit="lb", bars=(), plates=(), dumbbells=(20.0, 25.0, 35.0), stack_step=None)
    curl = ladder_for("dumbbell", "Dumbbell Curl", inv)
    r = suggest_progression(10, [_sr(10, 25.0)] * 3, [], "biceps", False, ladder=curl)
    assert r.action == ADD_WEIGHT
    assert r.suggested_weight == 35.0
    assert "move up to the 35 lb dumbbells" in r.reason


def test_top_of_the_equipment_holds_and_adds_reps():
    inv = Inventory(unit="lb", bars=(45.0,), plates=(Plate(25.0, 1),), dumbbells=(), stack_step=None)
    ladder = ladder_for("barbell", "Bench Press", inv)  # loads: 45, 95
    r = suggest_progression(8, [_sr(8, 95.0)] * 3, [], "chest", False, ladder=ladder)
    assert r.action == HOLD
    assert r.suggested_weight == 95.0
    assert "heaviest your equipment loads" in r.reason


def test_deload_lands_on_a_loadable_weight():
    sets = [_sr(5, 115.0), _sr(5, 115.0), _sr(2, 115.0, completed=False)]
    history = [_hist(-3, sets), _hist(-6, sets)]
    r = suggest_progression(5, sets, history, "chest", False, ladder=BENCH)
    assert r.action == DELOAD
    assert r.suggested_weight == 105.0  # 115 × 0.9 = 103.5 → nearest loadable below 115
    assert "deload to 105 lb" in r.reason


def test_deload_at_the_lightest_load_holds():
    sets = [_sr(5, 15.0), _sr(5, 15.0), _sr(2, 15.0, completed=False)]
    history = [_hist(-3, sets), _hist(-6, sets)]
    r = suggest_progression(5, sets, history, "chest", False, ladder=BENCH)
    assert r.action == HOLD
    assert r.suggested_weight == 15.0
    assert "lightest load" in r.reason


def test_without_a_ladder_the_legacy_step_stands():
    r = suggest_progression(8, _THREE_AT_8, [], "chest", False)
    assert r.suggested_weight == 117.5
