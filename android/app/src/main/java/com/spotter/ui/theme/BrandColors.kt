package com.spotter.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Brand extras that don't map onto a Material color role: the signature gradients and the
 * semantic accent colors (success / warning / streak-flame / celebration "volt") that the
 * athletic identity leans on. Provided through [LocalBrand] by [SpotterTheme] so any composable
 * can pull `SpotterTheme.brand`.
 */
data class BrandColors(
    val heroGradient: Brush,        // blue -> indigo, for headers/hero surfaces
    val energyGradient: Brush,      // orange -> amber, primary CTAs / celebration
    val flameGradient: Brush,       // amber -> orange -> red, the streak flame
    val voltGradient: Brush,        // volt -> green, confetti / PR pops
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val streak: Color,              // flame tint for the streak stat
    val celebration: Color,         // volt pop
)

fun lightBrandColors(): BrandColors = BrandColors(
    heroGradient = Brush.linearGradient(listOf(SpotterBlue, SpotterIndigo)),
    energyGradient = Brush.linearGradient(listOf(SpotterOrange, SpotterAmber)),
    flameGradient = Brush.verticalGradient(listOf(SpotterAmber, SpotterOrange, SpotterRed)),
    voltGradient = Brush.linearGradient(listOf(SpotterVolt, SpotterGreen)),
    success = SpotterGreen,
    onSuccess = Color.White,
    warning = SpotterAmber,
    onWarning = Color(0xFF3A2600),
    streak = SpotterOrange,
    celebration = Color(0xFF6FA800), // darkened volt that reads on light surfaces
)

fun darkBrandColors(): BrandColors = BrandColors(
    heroGradient = Brush.linearGradient(listOf(Color(0xFF3D63FF), Color(0xFF7A45F0))),
    energyGradient = Brush.linearGradient(listOf(SpotterOrange, SpotterAmber)),
    flameGradient = Brush.verticalGradient(listOf(SpotterAmber, SpotterOrange, SpotterRed)),
    voltGradient = Brush.linearGradient(listOf(SpotterVolt, SpotterGreen)),
    success = DarkSecondary,
    onSuccess = DarkOnSecondary,
    warning = SpotterAmber,
    onWarning = Color(0xFF3A2600),
    streak = Color(0xFFFF8A5C),
    celebration = SpotterVolt,
)

val LocalBrand = staticCompositionLocalOf { lightBrandColors() }
