package com.spotter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A compact stat: a big bold value over a quiet label, on a [SpotterCard]. The value can be a
 * plain string or, via [animatedValue], a rolling [AnimatedCounter]. [accent] tints the value
 * (e.g. the streak flame).
 */
@Composable
fun StatTile(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    animatedValue: Int? = null,
    valueSuffix: String = "",
    accent: Color? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    SpotterCard(modifier = modifier, contentPadding = 14.dp) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            icon?.invoke()
            if (animatedValue != null) {
                AnimatedCounter(
                    target = animatedValue,
                    suffix = valueSuffix,
                    style = MaterialTheme.typography.headlineMedium,
                )
            } else {
                Text(
                    text = value.orEmpty(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = accent ?: MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
