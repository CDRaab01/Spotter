"""Movement-family table + derivation tests. Pure, no DB.

Two halves: (1) guardrails on the curated table itself — every key is a seeded catalog name,
bodyweight never appears, increments match equipment, each family has exactly one reference
source — so nobody can add a typo'd or unloadable entry; (2) table-driven ``derive_weight``
cases pinning the normalisation, source gating, recency, rounding and clamping rules.
"""

import datetime
import importlib.util
import pathlib

import pytest

from app.limits import WEIGHT_BOUNDS_LB
from app.movement_families import (
    EVIDENCE_REPS_CAP,
    FAMILIES,
    Evidence,
    derive_weight,
    family_names,
    family_of,
)
from app.progression import estimate_1rm

D0 = datetime.date(2026, 6, 1)
_VERSIONS = pathlib.Path(__file__).resolve().parent.parent / "alembic" / "versions"


def _seed_catalog() -> dict[str, tuple[str, str]]:
    """name → (muscle_group, equipment) from the two seed migrations."""
    catalog: dict[str, tuple[str, str]] = {}
    for fname in ("0002_seed_exercises.py", "0009_seed_accessory_exercises.py"):
        spec = importlib.util.spec_from_file_location(fname[:-3], _VERSIONS / fname)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        for name, group, equipment in mod.EXERCISES:
            catalog[name] = (group, equipment)
    return catalog


def _ev(name, weight, reps, day=0):
    return Evidence(name=name, date=D0 + datetime.timedelta(days=day), weight=weight, reps=reps)


# ── table guardrails ──────────────────────────────────────────────────────────


def test_every_family_name_is_a_seeded_exercise():
    catalog = _seed_catalog()
    missing = sorted(set(FAMILIES) - set(catalog))
    assert not missing, f"not in the seed catalog: {missing}"


def test_bodyweight_movements_never_belong_to_a_family():
    catalog = _seed_catalog()
    offenders = [n for n in FAMILIES if catalog[n][1] == "bodyweight"]
    assert not offenders


def test_increment_matches_equipment():
    catalog = _seed_catalog()
    for name, entry in FAMILIES.items():
        expected = 5.0 if catalog[name][1] == "dumbbell" else 2.5
        assert entry.increment == expected, name


def test_ratios_are_sane_and_each_family_has_one_reference_source():
    by_family: dict[str, list[str]] = {}
    for name, entry in FAMILIES.items():
        assert 0 < entry.ratio <= 3, name
        by_family.setdefault(entry.family, []).append(name)
    for family, names in by_family.items():
        refs = [n for n in names if FAMILIES[n].ratio == 1.0 and FAMILIES[n].source]
        assert len(refs) == 1, f"{family}: {refs}"


def test_family_lookups():
    assert family_of("Barbell Row") == "horizontal_pull"
    assert family_of("Push-Up") is None
    assert "Dumbbell Row" in family_names(["horizontal_pull"])
    assert "Bench Press" not in family_names(["horizontal_pull"])
    assert family_names([]) == set()


# ── derive_weight ─────────────────────────────────────────────────────────────


def test_unknown_target_or_no_evidence_is_none():
    assert derive_weight("Push-Up", 8, [_ev("Barbell Row", 100, 5)]) is None
    assert derive_weight("Dumbbell Row", 8, []) is None
    assert derive_weight("Dumbbell Row", 8, [_ev("Bench Press", 200, 5)]) is None


def test_sibling_history_seeds_a_scaled_load():
    # Barbell Row 5@100 → e1RM 116.7 → ×0.45 → 52.5 e1RM → at 8 reps 41.4 → floor to 5 lb.
    assert derive_weight("Dumbbell Row", 8, [_ev("Barbell Row", 100.0, 5)]) == 40.0


def test_own_history_round_trips_exactly():
    # Identity: same lift, same reps, must come back as the same number (pins the epsilon).
    assert derive_weight("Barbell Row", 8, [_ev("Barbell Row", 100.0, 8)]) == 100.0
    assert derive_weight("Dumbbell Row", 10, [_ev("Dumbbell Row", 35.0, 10)]) == 35.0


def test_non_source_member_never_seeds_a_sibling():
    assert derive_weight("Barbell Row", 8, [_ev("Seated Cable Row", 300.0, 8)]) is None
    assert derive_weight("Barbell Back Squat", 5, [_ev("Leg Press", 600.0, 10)]) is None


def test_non_source_target_still_uses_its_own_history():
    assert derive_weight("Leg Press", 10, [_ev("Leg Press", 300.0, 10)]) == 300.0


def test_most_recent_session_per_member_wins_over_older_heavier():
    evidence = [_ev("Barbell Row", 120.0, 8, day=0), _ev("Barbell Row", 100.0, 8, day=5)]
    assert derive_weight("Barbell Row", 8, evidence) == 100.0


def test_same_day_sets_take_the_best():
    evidence = [_ev("Barbell Row", 95.0, 8), _ev("Barbell Row", 100.0, 8)]
    assert derive_weight("Barbell Row", 8, evidence) == 100.0


def test_strongest_member_across_the_family_wins():
    evidence = [_ev("Barbell Row", 60.0, 8), _ev("Dumbbell Row", 50.0, 8)]  # DB row implies 111
    assert derive_weight("T-Bar Row", 8, evidence) == 100.0  # 111.1 × 0.9 = 100


def test_reps_are_normalised_across_prescriptions():
    # 5×5 bench at 135 (e1RM 157.5) → 10-rep close-grip: 157.5×0.9/1.333 = 106.3 → 105.
    assert derive_weight("Close-Grip Bench Press", 10, [_ev("Bench Press", 135.0, 5)]) == 105.0


def test_high_rep_evidence_is_capped():
    capped = estimate_1rm(100.0, EVIDENCE_REPS_CAP)
    uncapped = estimate_1rm(100.0, 20)
    assert uncapped > capped
    got = derive_weight("Barbell Row", 1, [_ev("Barbell Row", 100.0, 20)])
    assert got == pytest.approx(capped - (capped % 2.5))


def test_target_reps_none_defaults_and_single_rep_is_the_e1rm():
    assert derive_weight("Barbell Row", None, [_ev("Barbell Row", 100.0, 8)]) == 100.0
    assert derive_weight("Barbell Row", 1, [_ev("Barbell Row", 100.0, 1)]) == 100.0


def test_result_is_clamped_to_bounds():
    _, hi = WEIGHT_BOUNDS_LB
    assert derive_weight("Rack Pull", 1, [_ev("Conventional Deadlift", 600.0, 1)]) == hi


def test_below_one_increment_is_none():
    assert derive_weight("Tricep Kickback", 12, [_ev("Skull Crusher", 10.0, 12)]) is None


def test_zero_or_negative_evidence_is_ignored():
    assert derive_weight("Barbell Row", 8, [_ev("Barbell Row", 0.0, 8)]) is None
