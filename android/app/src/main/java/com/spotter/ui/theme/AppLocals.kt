package com.spotter.ui.theme

import androidx.compose.runtime.compositionLocalOf
import com.spotter.util.WeightUnit
import com.spotter.util.DistanceUnit

val LocalWeightUnit = compositionLocalOf { WeightUnit.LBS }
val LocalDistanceUnit = compositionLocalOf { DistanceUnit.MI }

fun WeightUnit.formatWeight(lbs: Double): String =
    if (this == WeightUnit.KG) "${(lbs * 0.453592).toInt()} kg" else "${lbs.toInt()} lb"

fun WeightUnit.formatWeightNullable(lbs: Double?): String =
    if (lbs == null) "BW" else formatWeight(lbs)

fun WeightUnit.formatWeightFieldLabel(): String =
    if (this == WeightUnit.KG) "Weight (kg)" else "Weight (lb)"

fun WeightUnit.formatWeightLabel(): String =
    if (this == WeightUnit.KG) "kg" else "lb"

fun WeightUnit.formatVolume(totalLb: Int): String =
    if (this == WeightUnit.KG) "%,d kg".format((totalLb * 0.453592).toInt())
    else "%,d lb".format(totalLb)

fun WeightUnit.parseToLbs(input: String): Double? {
    val value = input.toDoubleOrNull() ?: return null
    return if (this == WeightUnit.KG) value / 0.453592 else value
}
