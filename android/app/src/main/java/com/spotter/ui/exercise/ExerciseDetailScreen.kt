package com.spotter.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.model.ExerciseProgressPoint
import com.spotter.data.model.PersonalRecord
import com.spotter.ui.components.ErrorState
import com.spotter.ui.components.LoadingState
import com.spotter.ui.progress.ChartCard
import com.spotter.ui.progress.LineChart
import com.spotter.ui.progress.StrengthMetric
import com.spotter.ui.progress.StrengthMetricToggle
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.formatWeight
import com.spotter.ui.theme.formatWeightNullable
import com.spotter.util.UiState
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

/**
 * Exercise detail: name, primary + secondary muscles, equipment and instructions (mirror-backed,
 * so they work offline), plus this exercise's own history — a Weight / Est. 1RM curve, its
 * personal records, and the most recent logged days. The history half is server-computed and has
 * no mirror, so offline it degrades to a quiet "unavailable" note with a retry rather than
 * fabricating numbers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(
    exerciseId: String,
    navController: NavController,
    viewModel: ExerciseDetailViewModel = hiltViewModel(),
) {
    val exercise by viewModel.exercise.collectAsState()
    val history by viewModel.history.collectAsState()

    LaunchedEffect(exerciseId) { viewModel.load(exerciseId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exercise") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val state = exercise) {
            is UiState.Loading -> LoadingState(Modifier.padding(padding))

            is UiState.Error -> ErrorState(
                message = state.message,
                modifier = Modifier.padding(padding),
                onRetry = { viewModel.load(exerciseId) },
            )

            is UiState.Success -> {
                val ex = state.data
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(SpotterTheme.spacing.lg)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.md),
                ) {
                    PanelCard(modifier = Modifier.fillMaxWidth()) {
                        Text(ex.name, style = MaterialTheme.typography.headlineSmall)
                        val subtitle = listOfNotNull(ex.muscleGroup, ex.equipment)
                            .joinToString(" · ")
                        if (subtitle.isNotEmpty()) {
                            Spacer(Modifier.height(SpotterTheme.spacing.xs))
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val secondary = ex.secondaryMuscles
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    if (ex.muscleGroup != null || secondary.isNotEmpty()) {
                        PanelCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader("Muscles")
                            Spacer(Modifier.height(SpotterTheme.spacing.sm))
                            ex.muscleGroup?.let { primary ->
                                LabelValueRow("Primary", primary)
                            }
                            if (secondary.isNotEmpty()) {
                                Spacer(Modifier.height(SpotterTheme.spacing.xs))
                                LabelValueRow("Secondary", secondary.joinToString(", "))
                            }
                        }
                    }

                    val instructions = ex.instructions?.takeIf { it.isNotBlank() }
                    PanelCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader("How to")
                        Spacer(Modifier.height(SpotterTheme.spacing.sm))
                        Text(
                            instructions ?: "No instructions for this exercise yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (instructions != null) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    HistorySection(
                        history = history,
                        onRetry = { viewModel.loadHistory(exerciseId) },
                    )
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun HistorySection(history: UiState<ExerciseHistory>, onRetry: () -> Unit) {
    val pulse = SpotterTheme.pulse
    when (history) {
        is UiState.Loading -> PanelCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(label = "Your history", channel = pulse.strength)
            Spacer(Modifier.height(SpotterTheme.spacing.md))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                    color = pulse.strength,
                )
            }
        }

        is UiState.Error -> PanelCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(label = "Your history", channel = pulse.strength)
            Spacer(Modifier.height(SpotterTheme.spacing.sm))
            Text(
                "History needs the server — it isn't stored on the phone. " +
                    "Try again once you're back online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SpotterTheme.spacing.sm))
            PulseButton(text = "Retry", onClick = onRetry, tonal = true, compact = true)
        }

        is UiState.Success -> {
            val data = history.data
            if (data.points.isEmpty()) {
                PanelCard(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(label = "Your history", channel = pulse.strength)
                    Spacer(Modifier.height(SpotterTheme.spacing.sm))
                    Text(
                        "No logged sets yet — this exercise will chart once you train it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                HistoryChartCard(data.points)
                data.record?.let { PersonalRecordCard(it) }
                RecentSetsCard(data.points)
            }
        }

        else -> Unit
    }
}

@Composable
private fun HistoryChartCard(points: List<ExerciseProgressPoint>) {
    val pulse = SpotterTheme.pulse
    // Est. 1RM only means something for weighted work — same rule as the Progress Strength tab.
    val hasEst1rm = points.any { it.est1rm != null }
    var selected by remember { mutableStateOf(StrengthMetric.WEIGHT) }
    val metric = if (hasEst1rm) selected else StrengthMetric.WEIGHT
    val series = when (metric) {
        StrengthMetric.WEIGHT -> points.mapNotNull { it.maxWeight?.toFloat() }
        StrengthMetric.EST_1RM -> points.mapNotNull { it.est1rm?.toFloat() }
    }

    Column {
        SectionHeader(label = "Your history", channel = pulse.strength)
        if (hasEst1rm) {
            StrengthMetricToggle(selected = selected, onSelect = { selected = it })
        }
        if (series.size >= 2) {
            ChartCard {
                LineChart(
                    points = series,
                    color = pulse.strength,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            }
        } else {
            Spacer(Modifier.height(SpotterTheme.spacing.sm))
            Text(
                "One session logged so far — the curve appears from the second.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PersonalRecordCard(record: PersonalRecord) {
    val weightUnit = LocalWeightUnit.current
    val pulse = SpotterTheme.pulse
    PanelCard(modifier = Modifier.fillMaxWidth(), channel = pulse.strength) {
        SectionHeader(label = "Personal records", channel = pulse.strength)
        Spacer(Modifier.height(SpotterTheme.spacing.sm))
        Row(modifier = Modifier.fillMaxWidth()) {
            RecordStat(
                label = "TOP WEIGHT",
                value = "${weightUnit.formatWeight(record.maxWeight)} × ${record.maxWeightReps}",
                modifier = Modifier.weight(1f),
            )
            RecordStat(
                label = "EST. 1RM",
                value = weightUnit.formatWeight(record.bestEst1rm),
                modifier = Modifier.weight(1f),
            )
            RecordStat(
                label = "BEST SET VOL",
                value = weightUnit.formatWeight(record.bestVolume),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(SpotterTheme.spacing.xs))
        Text(
            "Set on ${record.achievedOn}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecordStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DataText(
            text = value,
            style = SpotterTheme.dataType.numeral,
            color = SpotterTheme.pulse.strength,
        )
    }
}

/** The last few logged days, newest first — the numbers behind the curve. */
@Composable
private fun RecentSetsCard(points: List<ExerciseProgressPoint>) {
    val weightUnit = LocalWeightUnit.current
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader("Recent sets")
        Spacer(Modifier.height(SpotterTheme.spacing.sm))
        points.takeLast(5).reversed().forEach { point ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(point.date, style = MaterialTheme.typography.bodyMedium)
                DataText(
                    text = "${point.maxReps} × " +
                        weightUnit.formatWeightNullable(point.maxWeight),
                    style = SpotterTheme.dataType.numeral,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
