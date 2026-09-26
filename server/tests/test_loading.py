"""Pure loadable-weight model tests (app/loading.py). No DB.

The reported bug: suggestions like "+2 lb" — the engine added 2.5 lb to upper-body lifts, which on a
barbell is 1.25 lb a side, a plate most people don't own. These pin how each implement actually
loads (both sides of a bar, one side of a T-bar, fixed dumbbells, a stack's pin) and the ladder
queries the progression engine and session seeding are built on.
"""

import pytest

from app.loading import (
    BARBELL,
    DEFAULT_INVENTORY_KG,
    DEFAULT_INVENTORY_LB,
    DUMBBELL,
    KG_PER_LB,
    SINGLE,
    STACK,
    Inventory,
    Plate,
    describe_inventory,
    format_load,
    ladder_for,
    loading_mode,
)

HOME_GYM = Inventory(
    unit="lb",
    bars=(45.0,),
    plates=(Plate(45.0, 2), Plate(25.0, 1), Plate(10.0, 1), Plate(5.0, 1), Plate(2.5, 1)),
    dumbbells=(10.0, 15.0, 20.0, 25.0, 35.0, 50.0),
    stack_step=None,
)


def _bar(inv):
    return ladder_for("barbell", "Bench Press", inv)


# ── loading mode ─────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "equipment,name,mode",
    [
        ("barbell", "Bench Press", BARBELL),
        ("Barbell", "Barbell Back Squat", BARBELL),
        ("barbell", "T-Bar Row", SINGLE),  # plates go on one end
        ("dumbbell", "Dumbbell Curl", DUMBBELL),
        ("machine", "Leg Press", STACK),
        ("cable", "Lat Pulldown", STACK),
        ("bodyweight", "Pull-Up", None),
        (None, "Mystery Lift", None),
    ],
)
def test_loading_mode(equipment, name, mode):
    assert loading_mode(equipment, name) == mode


def test_bodyweight_has_no_ladder():
    assert ladder_for("bodyweight", "Push-Up", None) is None


# ── barbell: a plate on each side ────────────────────────────────────────────


def test_default_barbell_moves_in_five_pound_steps():
    """The default's smallest plate is 2.5 lb, so the bar moves 5 lb at a time — never 2.5."""
    ladder = _bar(None)
    assert ladder.next_up(115.0, 2.5) == 120.0
    assert not ladder.contains(117.5)
    assert ladder.contains(135.0) and ladder.contains(140.0)


def test_microplates_allow_two_and_a_half_pound_jumps():
    inv = Inventory(
        unit="lb",
        bars=(45.0,),
        plates=(Plate(45.0, 4), Plate(10.0, 2), Plate(5.0, 2), Plate(2.5, 2), Plate(1.25, 1)),
        dumbbells=(),
        stack_step=None,
    )
    assert _bar(inv).next_up(115.0, 2.5) == 117.5


def test_smallest_plate_five_means_ten_pound_jumps():
    inv = Inventory(
        unit="lb", bars=(45.0,), plates=(Plate(45.0, 4), Plate(10.0, 2), Plate(5.0, 2)),
        dumbbells=(), stack_step=None,
    )
    assert _bar(inv).next_up(135.0, 2.5) == 145.0


def test_plate_counts_cap_the_bar():
    """A home gym runs out of plates: 45 + 2 × (90 + 25 + 10 + 5 + 2.5) = 310 is the top."""
    ladder = _bar(HOME_GYM)
    assert ladder.loads[-1] == 310.0
    assert ladder.next_up(310.0, 5.0) is None


def test_limited_plates_use_the_right_combination():
    """Greedy would miss 95 (25 + 25 a side) with one pair each of 45 and 25 — the subset sum
    doesn't: 45 + 2 × 25 = 95."""
    inv = Inventory(
        unit="lb", bars=(45.0,), plates=(Plate(45.0, 1), Plate(25.0, 1)),
        dumbbells=(), stack_step=None,
    )
    assert _bar(inv).loads == (45.0, 95.0, 135.0, 185.0)


def test_every_bar_counts():
    """An EZ bar keeps curl loads below the Olympic bar reachable."""
    inv = Inventory(
        unit="lb", bars=(45.0, 25.0), plates=(Plate(10.0, 1), Plate(5.0, 1)),
        dumbbells=(), stack_step=None,
    )
    assert _bar(inv).loads[:4] == (25.0, 35.0, 45.0, 55.0)


def test_no_bars_means_no_barbell_ladder():
    inv = Inventory(unit="lb", bars=(), plates=(Plate(45.0, 2),), dumbbells=(), stack_step=None)
    assert _bar(inv) is None


# ── single-sided, dumbbells, stacks ──────────────────────────────────────────


def test_t_bar_adds_one_plate_at_a_time():
    ladder = ladder_for("barbell", "T-Bar Row", HOME_GYM)
    assert ladder.mode == SINGLE
    # Plates only, no bar weight; both plates of a pair can go on the one sleeve.
    assert ladder.loads[:4] == (2.5, 5.0, 7.5, 10.0)
    assert ladder.next_up(90.0, 2.5) == 92.5


def test_dumbbells_jump_to_the_next_pair_that_exists():
    ladder = ladder_for("dumbbell", "Dumbbell Curl", HOME_GYM)
    assert ladder.next_up(25.0, 2.5) == 35.0  # there are no 30s
    assert ladder.next_up(50.0, 2.5) is None


def test_stack_moves_by_its_pin():
    inv = Inventory(unit="lb", bars=(), plates=(), dumbbells=(), stack_step=10.0)
    ladder = ladder_for("cable", "Lat Pulldown", inv)
    assert ladder.next_up(100.0, 2.5) == 110.0
    assert ladder_for("cable", "Lat Pulldown", HOME_GYM) is None  # no stack listed


# ── ladder queries ───────────────────────────────────────────────────────────


def test_floor_is_the_heaviest_at_or_below():
    ladder = _bar(None)
    assert ladder.floor(117.5) == 115.0
    assert ladder.floor(120.0) == 120.0
    assert ladder.floor(10.0) is None  # below the lightest bar


def test_nearest_breaks_ties_toward_lighter():
    ladder = _bar(None)
    assert ladder.nearest(117.5) == 115.0
    assert ladder.nearest(118.0) == 120.0


def test_nearest_below_must_actually_reduce():
    ladder = _bar(None)
    assert ladder.nearest_below(103.5, ceiling=115.0) == 105.0
    assert ladder.nearest_below(14.0, ceiling=15.0) is None  # already the lightest load


# ── kg inventories ───────────────────────────────────────────────────────────


def test_kg_inventory_ladder_is_in_pounds_of_real_kg_loads():
    ladder = _bar(DEFAULT_INVENTORY_KG)
    sixty_kg = 60 / KG_PER_LB
    assert ladder.contains(sixty_kg)
    # 1.25 kg is the smallest plate → 2.5 kg jumps.
    assert ladder.next_up(sixty_kg, 2.5) == pytest.approx(62.5 / KG_PER_LB, abs=0.01)
    assert format_load(ladder.next_up(sixty_kg, 2.5), "kg") == "62.5 kg"


def test_format_load_trims_trailing_zeros():
    assert format_load(117.5, "lb") == "117.5 lb"
    assert format_load(120.0, "lb") == "120 lb"


# ── description for the coach ────────────────────────────────────────────────


def test_describe_inventory_states_the_barbell_jump():
    text = describe_inventory(DEFAULT_INVENTORY_LB)
    assert "45/35/15 lb bars" in text
    assert "a barbell moves in 5 lb steps (2.5 per side)" in text
    assert "dumbbells 5–100 lb in 5 lb steps" in text
    assert "machine/cable stacks in 5 lb steps" in text


def test_describe_inventory_lists_uneven_dumbbells():
    assert "dumbbells 10, 15, 20, 25, 35, 50 lb" in describe_inventory(HOME_GYM)
