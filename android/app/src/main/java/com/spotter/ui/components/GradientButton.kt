package com.spotter.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * MIGRATION SHIM over [PulseButton] — keeps unmigrated call sites compiling. The gradient is
 * gone: every primary action renders as a solid effort-channel block. New code should use
 * [PulseButton].
 */
@Deprecated("Use PulseButton.")
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradient: Brush? = null,
    contentColor: Color = Color.Unspecified,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    PulseButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
    )
}
