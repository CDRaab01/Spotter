package com.spotter.ui.cardio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotter.data.model.CardioPhase
import com.spotter.data.model.Interval
import com.spotter.ui.theme.SpotterTheme

/**
 * The segmented interval preview/progress bar: one block per phase, width proportional to its
 * duration. Run blocks use the brighter recovery green, walk/warm-up/cool-down a darker shade —
 * matching the screenshot's alternating bands. Pass [currentIndex] to outline the live block.
 */
@Composable
fun IntervalBar(
    intervals: List<Interval>,
    modifier: Modifier = Modifier,
    currentIndex: Int? = null,
    showLabels: Boolean = false,
) {
    val pulse = SpotterTheme.pulse
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(if (showLabels) 26.dp else 12.dp)
            .clip(RoundedCornerShape(6.dp)),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        intervals.forEachIndexed { index, interval ->
            val color = segmentColor(interval.phase, pulse.recovery, pulse.panelHigh)
            val isCurrent = index == currentIndex
            Box(
                modifier = Modifier
                    .weight(interval.durationSec.toFloat().coerceAtLeast(1f))
                    .fillMaxWidth()
                    .height(if (showLabels) 26.dp else 12.dp)
                    .background(color)
                    .then(
                        if (isCurrent) Modifier.border(1.5.dp, pulse.onRecovery) else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (showLabels && interval.durationSec >= 60) {
                    Text(
                        text = "${Math.round(interval.durationSec / 60.0).toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor(interval.phase, pulse.onRecovery),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(horizontal = 1.dp),
                    )
                }
            }
        }
    }
}

private fun segmentColor(phase: CardioPhase, recovery: Color, neutral: Color): Color = when (phase) {
    CardioPhase.RUN -> recovery
    CardioPhase.WALK -> recovery.copy(alpha = 0.45f)
    CardioPhase.WARM_UP, CardioPhase.COOL_DOWN -> recovery.copy(alpha = 0.22f)
}

private fun contentColor(phase: CardioPhase, onRecovery: Color): Color = when (phase) {
    CardioPhase.RUN -> onRecovery
    else -> Color.White
}
