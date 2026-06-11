package com.spotter.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * MIGRATION SHIM — the old gradient brand layer, re-based onto the PULSE palette so unmigrated
 * screens render in the new identity while they wait for their Phase-4 pass. The "gradients" are
 * now flat channel fills (PULSE has no decorative gradients). Deleted once no screen reads
 * [LocalBrand] / `SpotterTheme.brand`.
 */
@Deprecated("Use SpotterTheme.pulse channels instead.")
data class BrandColors(
    val heroGradient: Brush,
    val energyGradient: Brush,
    val flameGradient: Brush,
    val voltGradient: Brush,
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val streak: Color,
    val celebration: Color,
)

@Suppress("DEPRECATION")
fun lightBrandColors(): BrandColors = BrandColors(
    heroGradient = Brush.linearGradient(listOf(PulseCyanDeep, PulseCyanDeep)),
    energyGradient = Brush.linearGradient(listOf(PulseCyanDeep, PulseCyanDeep)),
    flameGradient = Brush.verticalGradient(listOf(PulseAmberDeep, PulseAmberDeep)),
    voltGradient = Brush.linearGradient(listOf(PulseGreenDeep, PulseGreenDeep)),
    success = PulseGreenDeep,
    onSuccess = Color.White,
    warning = PulseAmberDeep,
    onWarning = Color.White,
    streak = PulseAmberDeep,
    celebration = PulseGreenDeep,
)

@Suppress("DEPRECATION")
fun darkBrandColors(): BrandColors = BrandColors(
    heroGradient = Brush.linearGradient(listOf(PulsePanelHigh, PulsePanelHigh)),
    energyGradient = Brush.linearGradient(listOf(Color(0xFF0E7490), Color(0xFF0E7490))),
    flameGradient = Brush.verticalGradient(listOf(PulseAmber, PulseAmber)),
    voltGradient = Brush.linearGradient(listOf(PulseGreen, PulseGreen)),
    success = PulseGreen,
    onSuccess = Color(0xFF00301F),
    warning = PulseAmber,
    onWarning = Color(0xFF2B1B00),
    streak = PulseAmber,
    celebration = PulseGreen,
)

@Suppress("DEPRECATION")
val LocalBrand = staticCompositionLocalOf { lightBrandColors() }
