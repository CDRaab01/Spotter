package com.spotter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import design.pulse.ui.theme.LocalDataTypography
import design.pulse.ui.theme.LocalSpacing
import design.pulse.ui.theme.PulseAccent
import design.pulse.ui.theme.PulseAmber
import design.pulse.ui.theme.PulseBlue
import design.pulse.ui.theme.PulseBlueDeep
import design.pulse.ui.theme.PulseDataTypography
import design.pulse.ui.theme.PulseGreen
import design.pulse.ui.theme.PulseGreenDeep
import design.pulse.ui.theme.PulseIndigo
import design.pulse.ui.theme.PulseIndigoDeep
import design.pulse.ui.theme.PulseOrange
import design.pulse.ui.theme.PulseOrangeDeep
import design.pulse.ui.theme.PulsePanel
import design.pulse.ui.theme.PulsePanelHigh
import design.pulse.ui.theme.PulseTheme
import design.pulse.ui.theme.PulseViolet
import design.pulse.ui.theme.PulseVioletDeep
import design.pulse.ui.theme.Spacing

/**
 * Spotter's semantic layer over the shared PULSE library (`design.pulse:pulse-ui`). Generic tokens
 * (palette, type, motion, shape, structure) and generic components come from the library; only
 * Spotter's domain meaning lives here.
 *
 * Spotter leads BLUE via [PulseAccent.Blue]; channels — use only where the meaning matches:
 *  - effort:   volume, work, active timers, primary actions (electric blue = the accent)
 *  - strength: PRs, loads, strength charts (violet)
 *  - streak:   consistency (orange)
 *  - recovery: rest, completion, success (green)
 *
 * `base`/`dim`/`on` per channel; gradients reserved for hero moments: [heroGradient] (blue → indigo)
 * for greeting + primary CTAs is the signature; [energyGradient] (orange → amber) is the
 * streak/celebration voice. Provided via [LocalPulse]; pull with `SpotterTheme.pulse`. (The Blue M3
 * scheme the library derives from this accent matches Spotter's former in-tree scheme verbatim, so
 * no reconcile is needed.)
 */
@Immutable
data class PulseColors(
    val effort: Color,
    val effortDim: Color,
    val onEffort: Color,
    val strength: Color,
    val strengthDim: Color,
    val onStrength: Color,
    val streak: Color,
    val streakDim: Color,
    val onStreak: Color,
    val recovery: Color,
    val recoveryDim: Color,
    val onRecovery: Color,
    // Structure
    val hairline: Color,
    val hairlineStrong: Color,
    val panel: Color,
    val panelHigh: Color,
    val glow: Color,
    // Brand gradients
    val heroGradient: Brush,   // blue → indigo: greeting, primary CTAs
    val energyGradient: Brush, // orange → amber: streak/celebration moments
    // Content color for text/icons ON the energy gradient. Dark in BOTH themes (the gradient is
    // light orange/amber in both): white on it is only ~2.1:1, this warm ink is 7.6-8.8:1.
    val onEnergy: Color,
)

fun darkPulseColors() = PulseColors(
    effort = PulseBlue, effortDim = Color(0xFF151C33), onEffort = Color(0xFFFFFFFF),
    strength = PulseViolet, strengthDim = Color(0xFF231F3F), onStrength = Color(0xFF120A38),
    streak = PulseOrange, streakDim = Color(0xFF3B2418), onStreak = Color(0xFF2B1100),
    recovery = PulseGreen, recoveryDim = Color(0xFF11332A), onRecovery = Color(0xFF00301F),
    hairline = Color(0x14FFFFFF),
    hairlineStrong = Color(0x29FFFFFF),
    panel = PulsePanel,
    panelHigh = PulsePanelHigh,
    glow = PulseBlue,
    // Hero gradient starts at PulseBlueDeep (not PulseBlue): white CTA/greeting text needs >= 4.5:1
    // and white-on-PulseBlue is only 3.72:1, while white-on-PulseBlueDeep is 5.20:1.
    heroGradient = Brush.linearGradient(listOf(PulseBlueDeep, PulseIndigo)),
    energyGradient = Brush.linearGradient(listOf(PulseOrange, PulseAmber)),
    onEnergy = Color(0xFF2B1100),
)

fun lightPulseColors() = PulseColors(
    effort = PulseBlueDeep, effortDim = Color(0xFFECF1FF), onEffort = Color(0xFFFFFFFF),
    strength = PulseVioletDeep, strengthDim = Color(0xFFE6E2FB), onStrength = Color(0xFFFFFFFF),
    streak = PulseOrangeDeep, streakDim = Color(0xFFFBE3D4), onStreak = Color(0xFFFFFFFF),
    recovery = PulseGreenDeep, recoveryDim = Color(0xFFD8F3E8), onRecovery = Color(0xFFFFFFFF),
    hairline = Color(0x1A000000),
    hairlineStrong = Color(0x33000000),
    panel = Color(0xFFFFFFFF),
    panelHigh = Color(0xFFF1F3F6),
    glow = PulseBlueDeep,
    heroGradient = Brush.linearGradient(listOf(PulseBlueDeep, PulseIndigoDeep)),
    energyGradient = Brush.linearGradient(listOf(Color(0xFFFF6B35), PulseAmber)),
    onEnergy = Color(0xFF2B1100),
)

val LocalPulse = staticCompositionLocalOf { darkPulseColors() }

/**
 * The channel assigned to a program day by its position — day 1 orange, day 2 blue, day 3 violet,
 * day 4 green, then repeating. Keeps each day of a program visually distinct on the calendar and
 * anywhere else days are color-coded.
 */
fun PulseColors.dayChannel(dayIndex: Int): Color {
    val cycle = listOf(streak, effort, strength, recovery)
    return cycle[((dayIndex % cycle.size) + cycle.size) % cycle.size]
}

@Composable
fun SpotterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    PulseTheme(darkTheme = darkTheme, accent = PulseAccent.Blue) {
        CompositionLocalProvider(
            LocalPulse provides if (darkTheme) darkPulseColors() else lightPulseColors(),
        ) {
            content()
        }
    }
}

/** Convenience accessors mirroring `MaterialTheme.*`. */
object SpotterTheme {
    val pulse: PulseColors
        @Composable @ReadOnlyComposable get() = LocalPulse.current
    val dataType: PulseDataTypography
        @Composable @ReadOnlyComposable get() = LocalDataTypography.current
    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
}
