package com.spotter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = SpotterBlue,
    onPrimary = SpotterOnPrimary,
    secondary = SpotterGreen,
    background = SpotterBackground,
    surface = SpotterSurface,
    onBackground = SpotterOnBackground,
    error = SpotterRed,
)

private val DarkColors = darkColorScheme(
    primary = SpotterBlue,
    onPrimary = SpotterOnPrimary,
    secondary = SpotterGreen,
    background = SpotterDarkBg,
    surface = SpotterDarkSurface,
    surfaceVariant = SpotterDarkSurfaceVariant,
    onSurface = SpotterDarkOnSurface,
    onSurfaceVariant = SpotterDarkOnSurfaceVariant,
    error = SpotterRed,
)

@Composable
fun SpotterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
