package com.spotter.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The PULSE semantic layer: channel colors owned by data domains, plus the structural colors the
 * instrument-panel aesthetic is built from (hairline strokes, panel tones, glow).
 *
 * Channels — use them only where the meaning matches:
 *  - effort:   volume, work, active timers, primary actions
 *  - strength: PRs, loads, strength charts
 *  - streak:   consistency
 *  - recovery: rest, completion, success
 *
 * Each channel has a `base` (strokes, text, rings), a `dim` (container fill — a pre-composited
 * solid, not an alpha, so hairlines drawn on top stay predictable) and an `on` (content atop the
 * base fill). Provided through [LocalPulse] by SpotterTheme; pull via `SpotterTheme.pulse`.
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
)

fun darkPulseColors() = PulseColors(
    effort = PulseCyan, effortDim = Color(0xFF12333B), onEffort = Color(0xFF00252C),
    strength = PulseViolet, strengthDim = Color(0xFF231F3F), onStrength = Color(0xFF120A38),
    streak = PulseAmber, streakDim = Color(0xFF382A10), onStreak = Color(0xFF2B1B00),
    recovery = PulseGreen, recoveryDim = Color(0xFF11332A), onRecovery = Color(0xFF00301F),
    hairline = Color(0x14FFFFFF),
    hairlineStrong = Color(0x29FFFFFF),
    panel = PulsePanel,
    panelHigh = PulsePanelHigh,
    glow = PulseCyan,
)

fun lightPulseColors() = PulseColors(
    effort = PulseCyanDeep, effortDim = Color(0xFFD6F1F7), onEffort = Color(0xFFFFFFFF),
    strength = PulseVioletDeep, strengthDim = Color(0xFFE6E2FB), onStrength = Color(0xFFFFFFFF),
    streak = PulseAmberDeep, streakDim = Color(0xFFF8EED6), onStreak = Color(0xFFFFFFFF),
    recovery = PulseGreenDeep, recoveryDim = Color(0xFFD8F3E8), onRecovery = Color(0xFFFFFFFF),
    hairline = Color(0x1A000000),
    hairlineStrong = Color(0x33000000),
    panel = Color(0xFFFFFFFF),
    panelHigh = Color(0xFFF1F3F6),
    glow = PulseCyanDeep,
)

val LocalPulse = staticCompositionLocalOf { darkPulseColors() }
