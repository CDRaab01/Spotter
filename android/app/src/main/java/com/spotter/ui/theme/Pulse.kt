package com.spotter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The PULSE semantic layer: channel colors owned by data domains, the structural colors the
 * instrument-panel aesthetic is built from (hairline strokes, panel tones, glow), and the two
 * brand gradients.
 *
 * Channels — use them only where the meaning matches:
 *  - effort:   volume, work, active timers, primary actions (electric blue)
 *  - strength: PRs, loads, strength charts (violet)
 *  - streak:   consistency (orange)
 *  - recovery: rest, completion, success (green)
 *
 * Each channel has a `base` (strokes, text, rings), a `dim` (container fill — a pre-composited
 * solid, not an alpha, so hairlines drawn on top stay predictable) and an `on` (content atop the
 * base fill). Gradients are reserved for hero moments: [heroGradient] (blue → indigo) for the
 * greeting and primary CTAs is the signature; [energyGradient] (orange → amber) is the
 * celebration/streak voice. Provided through [LocalPulse] by SpotterTheme; pull via
 * `SpotterTheme.pulse`.
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
    val hairline: Color,        // 1px inner strokes on panels
    val hairlineStrong: Color,  // emphasized strokes (selected states)
    val panel: Color,
    val panelHigh: Color,
    val glow: Color,            // ring/dot glow base; draw at low alpha
    // Brand gradients
    val heroGradient: Brush,    // blue → indigo: greeting, primary CTAs
    val energyGradient: Brush,  // orange → amber: streak/celebration moments
)

fun darkPulseColors() = PulseColors(
    effort = PulseBlue, effortDim = Color(0xFF1B2440), onEffort = Color(0xFFFFFFFF),
    strength = PulseViolet, strengthDim = Color(0xFF231F3F), onStrength = Color(0xFF120A38),
    streak = PulseOrange, streakDim = Color(0xFF3B2418), onStreak = Color(0xFF2B1100),
    recovery = PulseGreen, recoveryDim = Color(0xFF11332A), onRecovery = Color(0xFF00301F),
    hairline = Color(0x14FFFFFF),
    hairlineStrong = Color(0x29FFFFFF),
    panel = PulsePanel,
    panelHigh = PulsePanelHigh,
    glow = PulseBlue,
    heroGradient = Brush.linearGradient(listOf(PulseBlue, PulseIndigo)),
    energyGradient = Brush.linearGradient(listOf(PulseOrange, PulseAmber)),
)

fun lightPulseColors() = PulseColors(
    effort = PulseBlueDeep, effortDim = Color(0xFFDEE7FF), onEffort = Color(0xFFFFFFFF),
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
)

val LocalPulse = staticCompositionLocalOf { darkPulseColors() }
