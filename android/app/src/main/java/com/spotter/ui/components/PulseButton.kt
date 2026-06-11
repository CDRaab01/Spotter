package com.spotter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spotter.ui.theme.SpotterTheme

/**
 * The primary action: a solid channel-colored block with press-scale and ripple. Defaults to the
 * effort channel. Use one per screen; secondary actions take [tonal] (dim fill, channel text) or
 * plain M3 buttons.
 *
 * Pass [channel]/[onChannel]/[dimChannel] together when an action belongs to another domain
 * (e.g. recovery green for "Finish workout").
 */
@Composable
fun PulseButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tonal: Boolean = false,
    compact: Boolean = false,
    channel: Color = SpotterTheme.pulse.effort,
    onChannel: Color = SpotterTheme.pulse.onEffort,
    dimChannel: Color = SpotterTheme.pulse.effortDim,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.small
    val interaction = remember { MutableInteractionSource() }
    val container = if (tonal) dimChannel else channel
    val content = if (tonal) channel else onChannel
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .pressScale(interaction)
            .clip(shape)
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = rememberRipple(color = content),
                enabled = enabled,
                onClick = onClick,
            )
            .heightIn(min = if (compact) 40.dp else 52.dp)
            .padding(horizontal = if (compact) SpotterTheme.spacing.lg else SpotterTheme.spacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    leadingIcon?.invoke()
                    Text(text, color = content)
                }
            }
        }
    }
}
