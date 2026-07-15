"""Canonical sanity bounds for workout values.

Single source of truth shared by the Pydantic write schemas (reject out-of-range
client input with 422), the AI plan-extraction layer (clamp whatever the model
returns), and the AI system prompt. Keep these in one place so the guardrail is
auditable in isolation.
"""

# (min, max) inclusive bounds
SETS_BOUNDS = (1, 10)
REPS_BOUNDS = (1, 50)
WEIGHT_BOUNDS_LB = (0.5, 600.0)
CALORIE_BOUNDS = (1200, 6000)
BODY_WEIGHT_BOUNDS_LB = (50.0, 1500.0)  # bodyweight metric (lbs)
BODYFAT_BOUNDS = (1.0, 70.0)  # body fat percentage
MEASUREMENT_BOUNDS = (1.0, 500.0)  # tape measurements, generous enough for both in and cm

# Cap on actions in one AI live-workout adjustment (extraction truncates, apply rejects).
MAX_ADJUSTMENT_ACTIONS = 6


def clamp_int(value: int, bounds: tuple[int, int]) -> int:
    lo, hi = bounds
    return max(lo, min(hi, value))


def clamp_weight(value: float | None) -> float | None:
    """Clamp a weight into bounds, leaving None (bodyweight) untouched."""
    if value is None:
        return None
    lo, hi = WEIGHT_BOUNDS_LB
    return max(lo, min(hi, value))
