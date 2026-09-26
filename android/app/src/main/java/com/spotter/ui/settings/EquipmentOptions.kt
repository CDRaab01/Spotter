package com.spotter.ui.settings

import com.spotter.data.model.EquipmentInventory
import com.spotter.data.model.EquipmentOut
import com.spotter.data.model.PlatePair
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The choices the equipment editor offers, per unit, plus the one-line summaries it and the
 * Settings row show. Pure (no Compose) so it's unit-testable. Values the user already has that
 * aren't in a standard list (a 55 lb plate the server sent back) are always merged in, so the
 * editor can never silently drop something it can't display.
 */
internal object EquipmentOptions {
    fun bars(unit: String): List<Double> =
        if (unit == "kg") listOf(20.0, 15.0, 10.0, 7.0) else listOf(45.0, 35.0, 25.0, 15.0)

    fun plates(unit: String): List<Double> =
        if (unit == "kg") listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25, 0.5)
        else listOf(45.0, 35.0, 25.0, 15.0, 10.0, 5.0, 2.5, 1.25)

    fun dumbbells(unit: String): List<Double> =
        if (unit == "kg") {
            listOf(1.0, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 7.0, 7.5, 8.0, 9.0, 10.0) +
                (5..20).map { it * 2.5 }
        } else {
            (1..12).map { it * 2.5 } + (7..20).map { it * 5.0 } + listOf(110.0, 120.0)
        }

    fun stackSteps(unit: String): List<Double> =
        if (unit == "kg") listOf(1.25, 2.5, 5.0, 10.0) else listOf(2.5, 5.0, 10.0, 15.0, 20.0)

    /** [standard] ∪ [owned], sorted — owned oddities are never hidden. */
    fun merged(standard: List<Double>, owned: List<Double>, descending: Boolean = false): List<Double> {
        val all = (standard + owned).distinct()
        return if (descending) all.sortedDescending() else all.sorted()
    }

    fun pairsFor(inv: EquipmentInventory, weight: Double): Int =
        inv.plates.firstOrNull { it.weight == weight }?.pairs ?: 0

    /** Sets the pair count for one plate size; 0 removes it. Keeps plates heaviest-first. */
    fun withPlatePairs(inv: EquipmentInventory, weight: Double, pairs: Int): EquipmentInventory {
        val others = inv.plates.filter { it.weight != weight }
        val next = if (pairs > 0) others + PlatePair(weight, pairs) else others
        return inv.copy(plates = next.sortedByDescending { it.weight })
    }

    /** The smallest barbell jump the plates allow (two of the smallest plate), or null. */
    fun smallestBarJump(inv: EquipmentInventory): Double? =
        inv.plates.filter { it.pairs > 0 }.minOfOrNull { it.weight }?.let { it * 2 }

    fun num(value: Double): String =
        BigDecimal(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** "5–50 lb in 5 lb steps" when evenly spaced, else the list. */
    fun dumbbellSummary(weights: List<Double>, unit: String): String {
        val ws = weights.sorted()
        if (ws.isEmpty()) return "none"
        if (ws.size >= 3) {
            val steps = ws.zipWithNext { a, b -> num(b - a) }.toSet()
            if (steps.size == 1) return "${num(ws.first())}–${num(ws.last())} $unit in ${steps.first()} $unit steps"
        }
        return ws.joinToString(", ") { num(it) } + " $unit"
    }

    /** The Settings-row subtitle. */
    fun summary(out: EquipmentOut): String {
        if (!out.configured) return "Not set — suggestions assume a standard gym"
        val inv = out.inventory
        val u = inv.unit
        val parts = mutableListOf<String>()
        if (inv.bars.isNotEmpty()) parts += inv.bars.joinToString("/") { num(it) } + " $u bar"
        val smallest = inv.plates.filter { it.pairs > 0 }.minOfOrNull { it.weight }
        if (smallest != null) parts += "plates down to ${num(smallest)} $u"
        if (inv.dumbbells.isNotEmpty()) parts += "dumbbells ${dumbbellSummary(inv.dumbbells, u)}"
        return if (parts.isEmpty()) "Nothing listed" else parts.joinToString(" · ")
    }
}
