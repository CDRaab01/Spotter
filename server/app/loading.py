"""Loadable weights — the loads the user's own equipment can actually make.

The progression engine used to reason in abstract pounds: "+2.5 lb upper body, +5 lb lower",
"deload 10 %", "floor to 2.5". None of that knows that a barbell takes a plate on **each** side,
that a 2.5 lb jump on a bar needs 1.25 lb plates most people don't own, that dumbbells come in
fixed steps, or that a cable stack moves by its pin. So it suggested loads nobody could put on the
bar (117.5 on a 45 lb bar with 2.5s as the smallest plate).

This module is the equipment model. :func:`ladder_for` turns the user's inventory plus an
exercise's equipment into a :class:`Ladder` — every load that implement can make, ascending, in
pounds — and the ladder answers the questions the rest of the server asks: the next load up, the
heaviest load at or below a target, the nearest load. Callers that get ``None`` (bodyweight,
unknown equipment, an implement the user listed none of) keep their legacy arithmetic.

How each implement loads:

* **barbell** — a bar plus a matching plate on *each* side, so the smallest jump is two of the
  smallest plate. Every listed bar counts (a union), so an EZ bar or a light fixed bar keeps
  curl/skull-crusher loads below the Olympic bar reachable.
* **single** — one-sided plate loading (T-bar / landmine rows): plates go on one at a time, no
  bar weight is counted (people log the plates).
* **dumbbell** — exactly the per-hand weights listed.
* **stack** — selectorized machines and cables: multiples of the pin step.

Pure functions of their inputs, no I/O; mirrored on the client in ``util/Loading.kt`` (same
defaults, same rounding) for the plate calculator and warm-up ramp.
"""

import functools
import itertools
from dataclasses import dataclass

from app.limits import WEIGHT_BOUNDS_LB

KG_PER_LB = 0.453592  # the constant the Android client converts with

BARBELL = "barbell"
SINGLE = "single"
DUMBBELL = "dumbbell"
STACK = "stack"

# Barbell-catalog lifts that are actually loaded from one end.
SINGLE_SIDED_NAMES = frozenset({"T-Bar Row"})

# Float slack when comparing loads: well under any real plate, well over conversion noise.
_EPS = 0.01
# Subset sums are computed on an integer grid of 1/100 of the inventory unit.
_GRID = 100


@dataclass(frozen=True)
class Plate:
    weight: float  # in the inventory's unit
    pairs: int  # how many matching pairs the user owns


@dataclass(frozen=True)
class Inventory:
    """What the user owns, in its own unit (plates are physical objects stamped in lb or kg).

    Hashable on purpose: :func:`ladder_for` caches ladders per (inventory, implement)."""

    unit: str  # "lb" | "kg"
    bars: tuple[float, ...]
    plates: tuple[Plate, ...]
    dumbbells: tuple[float, ...]
    stack_step: float | None


# The assumption for a user who hasn't told us: a normally-equipped commercial gym. Plate counts
# are generous enough that every multiple of the smallest plate pair is reachable up to the
# weight bound, so the only thing this default really asserts is "the smallest plate is 2.5 lb" —
# which is exactly what makes a barbell jump 5 lb, not 2.5.
DEFAULT_INVENTORY_LB = Inventory(
    unit="lb",
    bars=(45.0, 35.0, 15.0),
    plates=(
        Plate(45.0, 6),
        Plate(35.0, 1),
        Plate(25.0, 2),
        Plate(10.0, 2),
        Plate(5.0, 2),
        Plate(2.5, 2),
    ),
    dumbbells=tuple(float(w) for w in range(5, 105, 5)),
    stack_step=5.0,
)

DEFAULT_INVENTORY_KG = Inventory(
    unit="kg",
    bars=(20.0, 15.0, 10.0),
    plates=(
        Plate(25.0, 4),
        Plate(20.0, 2),
        Plate(15.0, 1),
        Plate(10.0, 2),
        Plate(5.0, 2),
        Plate(2.5, 2),
        Plate(1.25, 2),
    ),
    dumbbells=tuple(2.5 * i for i in range(1, 21)),
    stack_step=2.5,
)


def default_inventory(unit: str = "lb") -> Inventory:
    return DEFAULT_INVENTORY_KG if unit == "kg" else DEFAULT_INVENTORY_LB


def loading_mode(equipment: str | None, name: str | None = None) -> str | None:
    """How an exercise is loaded, from its catalog ``equipment`` (and name, for the one-sided
    exceptions). ``None`` = not load-modelled (bodyweight, bands, unknown)."""
    if name in SINGLE_SIDED_NAMES:
        return SINGLE
    eq = (equipment or "").strip().lower()
    if eq == BARBELL:
        return BARBELL
    if eq == DUMBBELL:
        return DUMBBELL
    if eq in ("machine", "cable"):
        return STACK
    return None


def to_lb(value: float, unit: str) -> float:
    return value / KG_PER_LB if unit == "kg" else value


def from_lb(value_lb: float, unit: str) -> float:
    return value_lb * KG_PER_LB if unit == "kg" else value_lb


def format_load(value_lb: float, unit: str) -> str:
    """``117.5 lb`` / ``60 kg`` — the load in the inventory's unit, trailing zeros trimmed."""
    v = round(from_lb(value_lb, unit), 2)
    text = f"{v:.2f}".rstrip("0").rstrip(".")
    return f"{text} {unit}"


def _subset_sums(plates: list[tuple[int, int]], limit: int) -> list[int]:
    """Every total (grid units, ≤ ``limit``) reachable by taking up to ``count`` of each plate."""
    mask = (1 << (limit + 1)) - 1
    reach = 1  # bit i set ⇔ total i reachable; the empty set reaches 0
    for weight, count in plates:
        if weight <= 0:
            continue
        for _ in range(count):
            nxt = (reach | (reach << weight)) & mask
            if nxt == reach:
                break  # already saturated — further copies add nothing below the limit
            reach = nxt
    return [i for i in range(limit + 1) if reach >> i & 1]


def _loads_in_unit(mode: str, inv: Inventory) -> list[float]:
    unit_max = from_lb(WEIGHT_BOUNDS_LB[1], inv.unit)
    grid_max = int(unit_max * _GRID + 0.5)

    if mode == BARBELL:
        bars = sorted({round(b * _GRID) for b in inv.bars if b > 0})
        if not bars:
            return []
        per_side = [(round(p.weight * _GRID), p.pairs) for p in inv.plates if p.pairs > 0]
        sides = _subset_sums(per_side, max(0, (grid_max - bars[0]) // 2))
        totals = {bar + 2 * s for bar in bars for s in sides if bar + 2 * s <= grid_max}
        return [t / _GRID for t in sorted(totals)]

    if mode == SINGLE:
        singles = [(round(p.weight * _GRID), 2 * p.pairs) for p in inv.plates if p.pairs > 0]
        return [t / _GRID for t in _subset_sums(singles, grid_max) if t > 0]

    if mode == DUMBBELL:
        return sorted({round(d, 2) for d in inv.dumbbells if 0 < d <= unit_max})

    if mode == STACK:
        step = inv.stack_step
        if not step or step <= 0:
            return []
        count = int(unit_max / step + 1e-9)
        return [round(step * k, 2) for k in range(1, count + 1)]

    return []


@dataclass(frozen=True)
class Ladder:
    """Every load one implement can make with the user's equipment, ascending, in pounds."""

    mode: str
    unit: str  # the inventory's unit — for human-readable reasons
    loads: tuple[float, ...]

    def next_up(self, current: float, min_step: float) -> float | None:
        """The lightest load at least ``min_step`` above ``current`` (and strictly heavier),
        or None when the equipment can't go that heavy."""
        floor = current + min_step - _EPS
        for x in self.loads:
            if x >= floor and x > current + _EPS:
                return x
        return None

    def floor(self, target: float) -> float | None:
        """The heaviest load at or below ``target`` — the conservative rounding."""
        best = None
        for x in self.loads:
            if x <= target + _EPS:
                best = x
            else:
                break
        return best

    def nearest(self, target: float) -> float | None:
        """The load closest to ``target``; ties go to the lighter one."""
        best = None
        for x in self.loads:
            if best is None or abs(x - target) < abs(best - target) - _EPS:
                best = x
        return best

    def nearest_below(self, target: float, ceiling: float) -> float | None:
        """The load closest to ``target`` that is still strictly lighter than ``ceiling`` —
        a deload must actually reduce the load. None when nothing lighter exists."""
        lighter = [x for x in self.loads if x < ceiling - _EPS]
        if not lighter:
            return None
        return min(lighter, key=lambda x: (abs(x - target), x))

    def contains(self, value: float) -> bool:
        return any(abs(x - value) <= _EPS for x in self.loads)


def _num(value: float) -> str:
    return f"{round(value, 2):.2f}".rstrip("0").rstrip(".")


def _dumbbell_summary(weights: tuple[float, ...], unit: str) -> str:
    ws = sorted(weights)
    if len(ws) >= 3:
        steps = {round(b - a, 2) for a, b in itertools.pairwise(ws)}
        if len(steps) == 1:
            return f"{_num(ws[0])}–{_num(ws[-1])} {unit} in {_num(steps.pop())} {unit} steps"
    return ", ".join(_num(w) for w in ws) + f" {unit}"


def describe_inventory(inv: Inventory) -> str:
    """One line for the coach's trusted context: what can be loaded, and the resulting jumps."""
    u = inv.unit
    parts: list[str] = []
    owned = [p for p in inv.plates if p.pairs > 0]
    if inv.bars:
        parts.append("/".join(_num(b) for b in inv.bars) + f" {u} bar" + ("s" if len(inv.bars) > 1 else ""))
    if owned:
        pairs = ", ".join(f"{_num(p.weight)}×{p.pairs}" for p in owned)
        smallest = min(p.weight for p in owned)
        parts.append(
            f"plate pairs {pairs} ({u}) — a barbell moves in {_num(2 * smallest)} {u} steps "
            f"({_num(smallest)} per side)"
        )
    if inv.dumbbells:
        parts.append("dumbbells " + _dumbbell_summary(inv.dumbbells, u))
    if inv.stack_step:
        parts.append(f"machine/cable stacks in {_num(inv.stack_step)} {u} steps")
    return "; ".join(parts) if parts else "none listed"


@functools.lru_cache(maxsize=256)
def _ladder(mode: str, inv: Inventory) -> Ladder | None:
    loads = _loads_in_unit(mode, inv)
    if not loads:
        return None
    lo, hi = WEIGHT_BOUNDS_LB
    in_lb = tuple(
        round(to_lb(x, inv.unit), 4)
        for x in loads
        if lo - _EPS <= to_lb(x, inv.unit) <= hi + _EPS
    )
    if not in_lb:
        return None
    return Ladder(mode=mode, unit=inv.unit, loads=in_lb)


def ladder_for(
    equipment: str | None, name: str | None, inventory: Inventory | None
) -> Ladder | None:
    """The load ladder for one exercise, or None when it isn't load-modelled (bodyweight,
    unknown equipment) or the user owns none of that implement."""
    mode = loading_mode(equipment, name)
    if mode is None:
        return None
    return _ladder(mode, inventory or DEFAULT_INVENTORY_LB)
