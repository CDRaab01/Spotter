package com.spotter.util

import com.spotter.data.model.EquipmentInventory
import com.spotter.data.model.PlatePair
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Loadable weights — the client mirror of the server's `app/loading.py` (same defaults, same
 * rounding), used where the phone does its own load arithmetic: the plate calculator and the
 * warm-up ramp. Progression suggestions and session seeds are snapped server-side.
 *
 * How each implement loads: a **barbell** is a bar plus a matching plate on *each* side (every
 * listed bar counts); **single**-sided loading (T-bar rows) adds plates one at a time with no bar
 * weight; **dumbbells** are exactly the listed weights; a **stack** moves by its pin step.
 */
object Loading {
    const val KG_PER_LB = 0.453592
    const val BARBELL = "barbell"
    const val SINGLE = "single"
    const val DUMBBELL = "dumbbell"
    const val STACK = "stack"

    private val SINGLE_SIDED_NAMES = setOf("T-Bar Row")

    /** Mirrors `app.limits.WEIGHT_BOUNDS_LB`. */
    private const val MIN_LB = 0.5
    private const val MAX_LB = 600.0

    internal const val EPS = 0.01
    private const val GRID = 100

    /** A normally-equipped commercial gym — what the server assumes until the user says otherwise. */
    val DEFAULT_LB = EquipmentInventory(
        unit = "lb",
        bars = listOf(45.0, 35.0, 15.0),
        plates = listOf(
            PlatePair(45.0, 6),
            PlatePair(35.0, 1),
            PlatePair(25.0, 2),
            PlatePair(10.0, 2),
            PlatePair(5.0, 2),
            PlatePair(2.5, 2),
        ),
        dumbbells = (5..100 step 5).map { it.toDouble() },
        stackStep = 5.0,
    )

    val DEFAULT_KG = EquipmentInventory(
        unit = "kg",
        bars = listOf(20.0, 15.0, 10.0),
        plates = listOf(
            PlatePair(25.0, 4),
            PlatePair(20.0, 2),
            PlatePair(15.0, 1),
            PlatePair(10.0, 2),
            PlatePair(5.0, 2),
            PlatePair(2.5, 2),
            PlatePair(1.25, 2),
        ),
        dumbbells = (1..20).map { it * 2.5 },
        stackStep = 2.5,
    )

    fun defaultFor(unit: String): EquipmentInventory = if (unit == "kg") DEFAULT_KG else DEFAULT_LB

    fun mode(equipment: String?, name: String?): String? {
        if (name in SINGLE_SIDED_NAMES) return SINGLE
        return when (equipment?.trim()?.lowercase()) {
            BARBELL -> BARBELL
            DUMBBELL -> DUMBBELL
            "machine", "cable" -> STACK
            else -> null
        }
    }

    fun toLb(value: Double, unit: String): Double = if (unit == "kg") value / KG_PER_LB else value
    fun fromLb(valueLb: Double, unit: String): Double = if (unit == "kg") valueLb * KG_PER_LB else valueLb

    private fun grid(value: Double): Int = (value * GRID).roundToInt()

    /** Every total (grid units, ≤ [limit]) reachable taking up to `count` of each (weight, count). */
    private fun subsetSums(items: List<Pair<Int, Int>>, limit: Int): BooleanArray {
        val reach = BooleanArray(limit + 1)
        reach[0] = true
        for ((w, count) in items) {
            if (w <= 0) continue
            repeat(count) {
                for (i in limit downTo w) if (!reach[i] && reach[i - w]) reach[i] = true
            }
        }
        return reach
    }

    /** Every load [mode] can make with [inv], ascending, in the inventory's own unit. */
    fun loadsInUnit(mode: String, inv: EquipmentInventory): List<Double> {
        val gridMax = grid(fromLb(MAX_LB, inv.unit))
        return when (mode) {
            BARBELL -> {
                val bars = inv.bars.filter { it > 0 }.map(::grid).distinct().sorted()
                if (bars.isEmpty()) return emptyList()
                val perSide = inv.plates.filter { it.pairs > 0 }.map { grid(it.weight) to it.pairs }
                val limit = ((gridMax - bars.first()) / 2).coerceAtLeast(0)
                val sides = subsetSums(perSide, limit)
                val totals = sortedSetOf<Int>()
                for (bar in bars) for (s in sides.indices) {
                    if (sides[s] && bar + 2 * s <= gridMax) totals += bar + 2 * s
                }
                totals.map { it.toDouble() / GRID }
            }
            SINGLE -> {
                val singles = inv.plates.filter { it.pairs > 0 }.map { grid(it.weight) to 2 * it.pairs }
                val sums = subsetSums(singles, gridMax)
                (1..gridMax).filter { sums[it] }.map { it.toDouble() / GRID }
            }
            DUMBBELL -> inv.dumbbells.filter { it > 0 && grid(it) <= gridMax }.distinct().sorted()
            STACK -> {
                val step = inv.stackStep ?: return emptyList()
                if (step <= 0) return emptyList()
                val count = (fromLb(MAX_LB, inv.unit) / step + 1e-9).toInt()
                (1..count).map { round2(step * it) }
            }
            else -> emptyList()
        }
    }

    /** The ladder for one exercise, or null when it isn't load-modelled or none of it is owned. */
    fun ladder(equipment: String?, name: String?, inv: EquipmentInventory): Ladder? {
        val mode = mode(equipment, name) ?: return null
        val loads = loadsInUnit(mode, inv)
            .map { toLb(it, inv.unit) }
            .filter { it >= MIN_LB - EPS && it <= MAX_LB + EPS }
        return if (loads.isEmpty()) null else Ladder(mode, inv.unit, loads)
    }

    /**
     * How to load [targetInUnit] (inventory unit) on [bar] with the owned plates: the heaviest
     * achievable total at or below the target, using the fewest plates. Null bar = one-sided
     * loading (both plates of every pair usable, no bar weight). Null when even the empty bar is
     * heavier than the target.
     */
    fun plateLoad(targetInUnit: Double, bar: Double?, plates: List<PlatePair>): PlateLoad? {
        val barW = bar ?: 0.0
        if (targetInUnit < barW - EPS) return null
        val sides = if (bar == null) 1 else 2
        val available = plates.filter { it.pairs > 0 }
            .sortedByDescending { it.weight }
            .map { grid(it.weight) to if (bar == null) 2 * it.pairs else it.pairs }
        val budget = ((targetInUnit - barW) / sides * GRID + 1e-6).toInt()
        val counts = IntArray(available.size)
        var best = IntArray(available.size)
        var bestSum = -1
        var bestCount = Int.MAX_VALUE
        fun dfs(i: Int, sum: Int, count: Int) {
            if (sum > bestSum || (sum == bestSum && count < bestCount)) {
                bestSum = sum
                bestCount = count
                best = counts.copyOf()
            }
            if (i == available.size || (bestSum == budget && count >= bestCount)) return
            val (w, have) = available[i]
            for (c in min(have, (budget - sum) / w) downTo 0) {
                counts[i] = c
                dfs(i + 1, sum + c * w, count + c)
            }
            counts[i] = 0
        }
        dfs(0, 0, 0)
        val perSide = available.indices
            .filter { best[it] > 0 }
            .map { available[it].first.toDouble() / GRID to best[it] }
        return PlateLoad(total = round2(barW + sides * bestSum.toDouble() / GRID), bar = bar, plates = perSide)
    }

    private fun round2(v: Double): Double = (v * 100).roundToLong() / 100.0
}

/**
 * Every load one implement can make with the user's equipment, ascending, in **pounds** (the app's
 * canonical unit); [unit] is the inventory's, for display.
 */
data class Ladder(val mode: String, val unit: String, val loads: List<Double>) {
    /** The lightest load at least [minStep] above [current] (and strictly heavier). */
    fun nextUp(current: Double, minStep: Double): Double? =
        loads.firstOrNull { it >= current + minStep - Loading.EPS && it > current + Loading.EPS }

    /** The heaviest load at or below [target]. */
    fun floor(target: Double): Double? = loads.lastOrNull { it <= target + Loading.EPS }

    /** The load closest to [target]; ties go to the lighter one. */
    fun nearest(target: Double): Double? {
        var best: Double? = null
        for (x in loads) {
            if (best == null || abs(x - target) < abs(best - target) - Loading.EPS) best = x
        }
        return best
    }

    /** The load closest to [target] that is strictly lighter than [ceiling]. */
    fun nearestBelow(target: Double, ceiling: Double): Double? =
        loads.filter { it < ceiling - Loading.EPS }.minWithOrNull(compareBy({ abs(it - target) }, { it }))

    fun contains(value: Double): Boolean = loads.any { abs(it - value) <= Loading.EPS }
}

/** How one exercise loads ([Loading.mode]; null = not load-modelled) and its ladder, if any. */
data class ExerciseLoad(val mode: String?, val ladder: Ladder?)

/**
 * A plate-calculator answer, in the inventory's unit: the achievable [total], the [bar] used (null
 * for one-sided loading) and the plates for **one** side (or the single sleeve), heaviest first.
 */
data class PlateLoad(val total: Double, val bar: Double?, val plates: List<Pair<Double, Int>>)
