package com.spotter.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * PULSE palette — a data-forward instrument panel.
 *
 * Identity: OLED-dark ink with hairline-stroked panels, and a *channel* color system where each
 * data domain owns a hue — effort (cyan), strength (violet), streak (amber), recovery (green).
 * Color always carries meaning, never decoration. Full Material 3 roles are specified for both
 * themes so anything reading `MaterialTheme.colorScheme` upgrades automatically; the semantic
 * channel layer lives in Pulse.kt.
 *
 * The raw channel seeds are tuned for dark surfaces; light theme MUST use the `*Deep` variants
 * (the seeds fail contrast on white — e.g. raw cyan is ~1.7:1).
 */

// ---- Reference palette ----------------------------------------------------------------------
val PulseInk = Color(0xFF0B0D10)         // dark background
val PulsePanel = Color(0xFF13161B)       // dark surface
val PulsePanelHigh = Color(0xFF1A1E25)   // raised dark surface

val PulseCyan = Color(0xFF22D3EE)        // effort — volume, work, timers
val PulseViolet = Color(0xFF8B7CFF)      // strength — PRs, loads
val PulseAmber = Color(0xFFFFB020)       // streak
val PulseGreen = Color(0xFF34D399)       // recovery — rest, done, success
val PulseRed = Color(0xFFFF5C5C)         // error (dark)

// Contrast-adapted channel variants for light surfaces (>= 4.5:1 on white).
val PulseCyanDeep = Color(0xFF0E7490)
val PulseVioletDeep = Color(0xFF5B4BD6)
val PulseAmberDeep = Color(0xFFA16207)
val PulseGreenDeep = Color(0xFF047857)
val PulseRedDeep = Color(0xFFDC2626)

// ---- Light scheme ---------------------------------------------------------------------------
val LightPrimary = PulseCyanDeep
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFD6F1F7)
val LightOnPrimaryContainer = Color(0xFF073B49)

val LightSecondary = PulseGreenDeep
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFD8F3E8)
val LightOnSecondaryContainer = Color(0xFF02382A)

val LightTertiary = PulseVioletDeep
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFE6E2FB)
val LightOnTertiaryContainer = Color(0xFF241C66)

val LightError = PulseRedDeep
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFBE0E0)
val LightOnErrorContainer = Color(0xFF5C0E0E)

val LightBackground = Color(0xFFF4F6F8)
val LightOnBackground = Color(0xFF14181D)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF14181D)
val LightSurfaceVariant = Color(0xFFECEEF2)
val LightOnSurfaceVariant = Color(0xFF525A66)
val LightOutline = Color(0xFFC9CDD4)
val LightOutlineVariant = Color(0x1A000000)   // the 1px hairline, as an M3 role too

// ---- Dark scheme ----------------------------------------------------------------------------
val DarkPrimary = PulseCyan
val DarkOnPrimary = Color(0xFF00252C)
val DarkPrimaryContainer = Color(0xFF12333B)
val DarkOnPrimaryContainer = Color(0xFFBDF0FA)

val DarkSecondary = PulseGreen
val DarkOnSecondary = Color(0xFF00301F)
val DarkSecondaryContainer = Color(0xFF11332A)
val DarkOnSecondaryContainer = Color(0xFFB9F2DC)

val DarkTertiary = PulseViolet
val DarkOnTertiary = Color(0xFF120A38)
val DarkTertiaryContainer = Color(0xFF231F3F)
val DarkOnTertiaryContainer = Color(0xFFDAD4FF)

val DarkError = PulseRed
val DarkOnError = Color(0xFF3D0202)
val DarkErrorContainer = Color(0xFF4A1414)
val DarkOnErrorContainer = Color(0xFFFFD3D3)

val DarkBackground = PulseInk
val DarkOnBackground = Color(0xFFE7EAF0)
val DarkSurface = PulsePanel
val DarkOnSurface = Color(0xFFE7EAF0)
val DarkSurfaceVariant = PulsePanelHigh
val DarkOnSurfaceVariant = Color(0xFF9AA3B2)
val DarkOutline = Color(0xFF2A2F38)
val DarkOutlineVariant = Color(0x14FFFFFF)    // the 1px hairline, as an M3 role too
