package com.spotter.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Spotter palette — "bold & athletic".
 *
 * Identity: an electric blue primary paired with an energetic orange accent (the combo most
 * sports brands lean on), a confident success green, and a "volt" lime reserved for celebration
 * gradients. Full Material 3 color roles are specified for both light and dark so every screen
 * that reads from `MaterialTheme.colorScheme` upgrades automatically. Brand gradients live in
 * BrandColors.kt.
 */

// ---- Brand seeds ---------------------------------------------------------------------------
val SpotterBlue = Color(0xFF2A5BFF)        // electric primary
val SpotterIndigo = Color(0xFF5B2BE0)      // hero-gradient partner
val SpotterOrange = Color(0xFFFF6B35)      // energy accent (streaks, PRs, CTAs)
val SpotterVolt = Color(0xFFC6F432)        // celebration pop
val SpotterGreen = Color(0xFF00B368)       // success
val SpotterRed = Color(0xFFE23D2E)         // error/destructive
val SpotterAmber = Color(0xFFF5A623)       // warning

// ---- Light scheme --------------------------------------------------------------------------
val LightPrimary = SpotterBlue
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDCE3FF)
val LightOnPrimaryContainer = Color(0xFF06157A)

val LightSecondary = SpotterGreen
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFB7F2D2)
val LightOnSecondaryContainer = Color(0xFF00351D)

val LightTertiary = SpotterOrange
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFDBCB)
val LightOnTertiaryContainer = Color(0xFF3A1300)

val LightError = SpotterRed
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD5)
val LightOnErrorContainer = Color(0xFF410100)

val LightBackground = Color(0xFFF4F6FB)
val LightOnBackground = Color(0xFF12141C)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF12141C)
val LightSurfaceVariant = Color(0xFFE6E9F3)
val LightOnSurfaceVariant = Color(0xFF454A5C)
val LightOutline = Color(0xFFBFC4D4)
val LightOutlineVariant = Color(0xFFDDE1EC)

// ---- Dark scheme ---------------------------------------------------------------------------
val DarkPrimary = Color(0xFF7C97FF)
val DarkOnPrimary = Color(0xFF002089)
val DarkPrimaryContainer = Color(0xFF1F3DBB)
val DarkOnPrimaryContainer = Color(0xFFDCE3FF)

val DarkSecondary = Color(0xFF4CE095)
val DarkOnSecondary = Color(0xFF00391E)
val DarkSecondaryContainer = Color(0xFF005230)
val DarkOnSecondaryContainer = Color(0xFFB7F2D2)

val DarkTertiary = Color(0xFFFF8A5C)
val DarkOnTertiary = Color(0xFF551B00)
val DarkTertiaryContainer = Color(0xFF7A2E10)
val DarkOnTertiaryContainer = Color(0xFFFFDBCB)

val DarkError = Color(0xFFFF6B5E)
val DarkOnError = Color(0xFF5F0001)
val DarkErrorContainer = Color(0xFF8B0F0A)
val DarkOnErrorContainer = Color(0xFFFFDAD5)

val DarkBackground = Color(0xFF0C0E14)
val DarkOnBackground = Color(0xFFE6E8F2)
val DarkSurface = Color(0xFF14161F)
val DarkOnSurface = Color(0xFFE6E8F2)
val DarkSurfaceVariant = Color(0xFF2A2E3C)
val DarkOnSurfaceVariant = Color(0xFFC2C7D6)
val DarkOutline = Color(0xFF4A4F61)
val DarkOutlineVariant = Color(0xFF323748)
