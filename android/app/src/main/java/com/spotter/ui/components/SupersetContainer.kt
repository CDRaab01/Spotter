package com.spotter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.spotter.ui.theme.SpotterTheme

/**
 * A visual bracket that pairs the exercises of one superset (the Hevy/Strong convention): a
 * "SUPERSET A · shared rest" header above the member cards, which are indented behind a strength-
 * channel accent rail so they read as one grouped block. The member cards themselves are supplied
 * as [content] (each tagged A1/A2 by the caller), so this composable stays layout-only and works for
 * both workout mode and routine detail.
 */
@Composable
fun SupersetContainer(
    groupLabel: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pulse = SpotterTheme.pulse
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = "SUPERSET $groupLabel",
                style = MaterialTheme.typography.labelSmall,
                color = pulse.strength,
            )
            Text(
                text = " · shared rest",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(SpotterTheme.spacing.xs))
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // The accent rail stretches to the height of the member column, forming the bracket.
            Spacer(
                Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(pulse.strength),
            )
            Spacer(Modifier.width(SpotterTheme.spacing.sm))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
                content = content,
            )
        }
    }
}

/** A small strength-channel position tag ("A1"/"A2") shown at the top of a superset member card. */
@Composable
fun SupersetPositionTag(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = SpotterTheme.pulse.strength,
        modifier = modifier.padding(bottom = SpotterTheme.spacing.xs),
    )
}
