package com.spotter.ui.workout

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.spotter.data.model.MuscleGroupSummary
import com.spotter.ui.components.CelebrationPulse
import com.spotter.ui.components.ConfettiHost
import com.spotter.ui.components.DataText
import com.spotter.ui.components.HeatBar
import com.spotter.ui.components.PanelCard
import com.spotter.ui.components.PulseButton
import com.spotter.ui.components.SectionHeader
import com.spotter.ui.components.TickerNumber
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.formatVolume
import com.spotter.ui.theme.formatWeight

@Composable
fun WorkoutSummaryScreen(
    durationSeconds: Int,
    doneSets: Int,
    totalSets: Int,
    totalVolumeLb: Int,
    muscleGroups: List<MuscleGroupSummary> = emptyList(),
    newPrCount: Int = 0,
    navController: NavController,
) {
    val weightUnit = LocalWeightUnit.current
    val pulse = SpotterTheme.pulse
    val spacing = SpotterTheme.spacing
    val perfect = totalSets > 0 && doneSets == totalSets
    val haptics = LocalHapticFeedback.current

    var play by remember { mutableStateOf(false) }
    val badgeScale by animateFloatAsState(
        targetValue = if (play) 1f else 0f,
        animationSpec = tween(500, easing = EaseOutBack),
        label = "badge",
    )
    LaunchedEffect(Unit) {
        play = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero: a quiet panel with the recovery ring-check, not a billboard.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 64.dp, bottom = spacing.xl, start = spacing.xl, end = spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CelebrationPulse(
                    modifier = Modifier.size(120.dp).scale(badgeScale),
                    channel = pulse.recovery,
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .background(pulse.recoveryDim, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = pulse.recovery,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                }
                Spacer(Modifier.height(spacing.lg))
                Text(
                    text = if (perfect) "Perfect session" else "Session complete",
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (perfect) "Every set logged. That's how it's done."
                           else "Another one in the books.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = spacing.xs),
                )
                if (newPrCount > 0) {
                    Spacer(Modifier.height(spacing.lg))
                    Row(
                        modifier = Modifier
                            .scale(badgeScale)
                            .clip(CircleShape)
                            .background(pulse.strengthDim)
                            .padding(horizontal = spacing.lg, vertical = spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Default.EmojiEvents,
                            contentDescription = null,
                            tint = pulse.strength,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = if (newPrCount == 1) "New personal record"
                                   else "$newPrCount new personal records",
                            style = MaterialTheme.typography.labelLarge,
                            color = pulse.strength,
                        )
                    }
                }
            }

            // The centerpiece: total volume as the one oversized readout on the screen.
            if (totalVolumeLb > 0) {
                DataText(
                    text = weightUnit.formatVolume(totalVolumeLb),
                    style = SpotterTheme.dataType.dataXL,
                    color = pulse.effort,
                )
                Text(
                    text = "TOTAL VOLUME",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.xl))
            } else {
                Spacer(Modifier.height(spacing.sm))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                SummaryStatCard(
                    modifier = Modifier.weight(1f),
                    valueContent = {
                        DataText(
                            text = "%02d:%02d".format(durationSeconds / 60, durationSeconds % 60),
                            style = SpotterTheme.dataType.dataMedium,
                        )
                    },
                    label = "DURATION",
                )
                SummaryStatCard(
                    modifier = Modifier.weight(1f),
                    valueContent = {
                        Row(verticalAlignment = Alignment.Bottom) {
                            TickerNumber(
                                target = doneSets,
                                style = SpotterTheme.dataType.dataMedium,
                                color = if (perfect) pulse.recovery
                                        else MaterialTheme.colorScheme.onSurface,
                            )
                            DataText(
                                text = "/$totalSets",
                                style = SpotterTheme.dataType.dataSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    label = "SETS DONE",
                )
            }

            if (muscleGroups.isNotEmpty()) {
                Spacer(Modifier.height(spacing.xl))
                SectionHeader(
                    title = "Muscles trained",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.lg),
                )
                Spacer(Modifier.height(spacing.sm))
                PanelCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.lg),
                ) {
                    val maxVolume = muscleGroups.maxOf { it.volume }.coerceAtLeast(1f)
                    Column(verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                        muscleGroups.forEach { mg ->
                            val volumeStr = if (mg.volume > 0f) {
                                weightUnit.formatWeight(mg.volume.toDouble() / 0.453592)
                            } else {
                                "${mg.sets} sets"
                            }
                            HeatBar(
                                label = mg.muscleGroup,
                                value = if (mg.volume > 0f) mg.volume else mg.sets.toFloat(),
                                maxValue = if (mg.volume > 0f) maxVolume
                                           else muscleGroups.maxOf { it.sets }.toFloat(),
                                valueText = volumeStr,
                                channel = pulse.effort,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
            PulseButton(
                text = "Return to Home",
                onClick = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.lg),
            )
            Spacer(Modifier.height(spacing.xxl))
        }

        // Celebration burst on entry; a perfect session gets it regardless of size.
        ConfettiHost(play = play)
    }
}

@Composable
private fun SummaryStatCard(
    label: String,
    valueContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    PanelCard(modifier = modifier) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            valueContent()
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
