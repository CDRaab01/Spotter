package com.spotter.ui.theme

import androidx.compose.runtime.compositionLocalOf
import com.spotter.util.WeightUnit
import com.spotter.util.DistanceUnit

val LocalWeightUnit = compositionLocalOf { WeightUnit.LBS }
val LocalDistanceUnit = compositionLocalOf { DistanceUnit.MI }

/**
 * "117.5 lb" / "61.2 kg": rounded to one decimal, trailing ".0" dropped. It used to *truncate*
 * (`toInt()`), so a suggested 117.5 read "117 lb" — a "+2 lb" jump nobody could load.
 */
fun WeightUnit.formatWeight(lbs: Double): String =
    "${formatLoadNumber(toDisplay(lbs))} ${if (this == WeightUnit.KG) "kg" else "lb"}"

/** One decimal, rounded half-up, without a trailing ".0". */
internal fun formatLoadNumber(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

fun WeightUnit.formatWeightNullable(lbs: Double?): String =
    if (lbs == null) "BW" else formatWeight(lbs)

/** Converts a stored lbs value into the user's display unit (no suffix). */
fun WeightUnit.toDisplay(lbs: Double): Double =
    if (this == WeightUnit.KG) lbs * 0.453592 else lbs

/** A bare, editable display-unit number for inline weight fields (trims a trailing .0). */
fun WeightUnit.fieldValue(lbs: Double): String {
    val v = toDisplay(lbs)
    return if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
}

fun WeightUnit.formatWeightFieldLabel(): String =
    if (this == WeightUnit.KG) "Weight (kg)" else "Weight (lb)"

fun WeightUnit.formatWeightLabel(): String =
    if (this == WeightUnit.KG) "kg" else "lb"

/** Tape measurements pair with the weight system: metric → cm, imperial → in. */
fun WeightUnit.measurementLabel(): String =
    if (this == WeightUnit.KG) "cm" else "in"

fun WeightUnit.formatVolume(totalLb: Int): String =
    if (this == WeightUnit.KG) "%,d kg".format((totalLb * 0.453592).toInt())
    else "%,d lb".format(totalLb)

fun WeightUnit.parseToLbs(input: String): Double? {
    val value = input.toDoubleOrNull() ?: return null
    return if (this == WeightUnit.KG) value / 0.453592 else value
}

// ---------------------------------------------------------------------------
// Distance: canonical storage is whole meters; the user enters/reads their unit.
// ---------------------------------------------------------------------------

private const val METERS_PER_MILE = 1609.344
private const val METERS_PER_KM = 1000.0

/** Short unit suffix for labels ("mi" / "km"). */
fun DistanceUnit.label(): String = if (this == DistanceUnit.KM) "km" else "mi"

/** Parse a display-unit distance string into canonical whole meters (null if blank/invalid). */
fun DistanceUnit.parseToMeters(input: String): Int? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val value = trimmed.toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
    val perUnit = if (this == DistanceUnit.KM) METERS_PER_KM else METERS_PER_MILE
    return Math.round(value * perUnit).toInt()
}

/** Convert canonical meters back into the user's unit (unrounded, for a field value). */
fun DistanceUnit.metersToDisplay(meters: Int): Double {
    val perUnit = if (this == DistanceUnit.KM) METERS_PER_KM else METERS_PER_MILE
    return meters / perUnit
}

/** A compact display string like "3.11 mi" for a stored meters value. */
fun DistanceUnit.formatDistance(meters: Int): String =
    "%.2f %s".format(metersToDisplay(meters), label())
