"""Pure progressive-overload engine tests (ROADMAP2 T3 #1). Table-driven, no DB.

Covers double progression (add_weight when reps met / add_reps when short), the stall→deload ladder
at the exact threshold, the weight-limit hold, bodyweight, e1RM + PR detection, the no-target linear
fallback, and stall-streak breaks (weight change / a prior success).
"""

import datetime

import pytest

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
