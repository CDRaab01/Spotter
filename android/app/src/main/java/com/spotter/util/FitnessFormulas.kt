package com.spotter.util

import kotlin.math.roundToInt

fun estimatedOneRM(weightLbs: Double, reps: Int): Double =
    if (reps <= 1) weightLbs else weightLbs * (1 + reps / 30.0)

/** A single ramp-up set leading into the working weight. */
data class WarmUpSet(val percent: Int, val weightLbs: Double, val reps: Int)

/**
 * Ramp-up sets for a compound lift, following the coaching prompt's scheme
 * (40% × 8, 60% × 5, 80% × 2–3 → working sets). With the lift's [ladder] each weight is the
 * nearest load the user's equipment makes (the empty bar at the bottom); without one it is
 * rounded to the nearest [roundToLbs]. A ramp set that would land at or above the working
 * weight is dropped. Returns an empty list for non-positive working weights (e.g. bodyweight).
 */
fun warmupSets(
    workingWeightLbs: Double,
    roundToLbs: Double = 5.0,
    ladder: Ladder? = null,
): List<WarmUpSet> {
    if (workingWeightLbs <= 0.0) return emptyList()
    val scheme = listOf(40 to 8, 60 to 5, 80 to 3)
    return scheme.mapNotNull { (percent, reps) ->
        val raw = workingWeightLbs * percent / 100.0
        val rounded = ladder?.nearest(raw) ?: ((raw / roundToLbs).roundToInt() * roundToLbs)
        if (rounded <= 0.0 || rounded >= workingWeightLbs - Loading.EPS) null
        else WarmUpSet(percent, rounded, reps)
    }
}
