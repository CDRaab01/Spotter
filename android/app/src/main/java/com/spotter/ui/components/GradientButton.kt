package com.spotter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spotter.ui.theme.SpotterTheme

/**
 * The hero call-to-action: a gradient pill with press-scale and ripple. Defaults to the brand
 * energy gradient (orange → amber). Use for the single primary action on a screen; secondary
 * actions should stay as plain M3 buttons.
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradient: Brush = SpotterTheme.brand.energyGradient,
    contentColor: Color = Color.White,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.large
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .pressScale(interaction)
            .clip(shape)
            .background(gradient)
            .clickable(
                interactionSource = interaction,
                indication = rememberRipple(color = Color.White),
                enabled = enabled,
                onClick = onClick,
            )
            .heightIn(min = 52.dp)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leadingIcon?.invoke()
                    Text(text, color = contentColor)
                }
            }
        }
    }
}
