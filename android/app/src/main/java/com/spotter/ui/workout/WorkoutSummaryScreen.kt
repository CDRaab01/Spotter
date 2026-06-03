package com.spotter.ui.workout

import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.spotter.data.model.MuscleGroupSummary
import com.spotter.ui.components.AnimatedCounter
import com.spotter.ui.components.ConfettiHost
import com.spotter.ui.components.GradientButton
import com.spotter.ui.components.SpotterCard
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.formatVolume
import com.spotter.ui.theme.formatWeight

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutSummaryScreen(
    durationSeconds: Int,
    doneSets: Int,
    totalSets: Int,
    totalVolumeLb: Int,
    muscleGroups: List<MuscleGroupSummary> = emptyList(),
    navController: NavController,
) {
    val weightUnit = LocalWeightUnit.current
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
            // Hero header with the brand gradient.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SpotterTheme.brand.heroGradient)
                    .padding(top = 64.dp, bottom = 40.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .scale(badgeScale)
                        .background(Color.White.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(60.dp),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = if (perfect) "Perfect session!" else "Great work!",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (perfect) "Every set logged. That's how it's done."
                           else "Another one in the books.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            // Stat tiles with rolling counters.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SummaryStatCard(
                    modifier = Modifier.weight(1f),
                    valueContent = {
                        Text(
                            "%02d:%02d".format(durationSeconds / 60, durationSeconds % 60),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    },
                    label = "Duration",
                )
                SummaryStatCard(
                    modifier = Modifier.weight(1f),
                    valueContent = {
                        Row {
                            AnimatedCounter(target = doneSets, style = MaterialTheme.typography.headlineMedium)
                            Text(
                                " / $totalSets",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    label = "Sets done",
                )
            }

            if (totalVolumeLb > 0) {
                Spacer(Modifier.height(12.dp))
                SpotterCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = weightUnit.formatVolume(totalVolumeLb),
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Text(
                            text = "total volume lifted",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (muscleGroups.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Muscles trained",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    muscleGroups.forEach { mg ->
                        val volumeKg = mg.volume
                        val volumeStr = if (volumeKg > 0f) {
                            " · ${weightUnit.formatWeight(volumeKg.toDouble() / 0.453592)}"
                        } else ""
                        SuggestionChip(
                            onClick = {},
                            label = { Text("${mg.muscleGroup}: ${mg.sets} sets$volumeStr") },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
            GradientButton(
                text = "Return to Home",
                onClick = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(32.dp))
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
    SpotterCard(modifier = modifier) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            valueContent()
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
