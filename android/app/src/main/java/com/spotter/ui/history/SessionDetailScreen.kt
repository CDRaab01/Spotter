package com.spotter.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SetLogOut
import com.spotter.ui.components.ErrorState
import com.spotter.ui.components.LoadingState
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.formatWeight
import com.spotter.util.UiState
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

/**
 * Read-only breakdown of a past workout: every set's actual reps and weight, per-exercise
 * notes, the session note, and the muscle-group summary. History previously dead-ended on
 * completed sessions — the counts were visible but never the lifts behind them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    navController: NavController,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val sessionState by viewModel.session.collectAsState()

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = (sessionState as? UiState.Success)?.data
                        ?.let { formatDate(it.date) } ?: "Workout"
                    Text(title)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val state = sessionState) {
            is UiState.Loading -> LoadingState(Modifier.padding(padding))

            is UiState.Error -> ErrorState(
                message = state.message,
                modifier = Modifier.padding(padding),
                onRetry = { viewModel.load(sessionId) },
            )

            is UiState.Success -> SessionDetailContent(
                session = state.data,
                modifier = Modifier.padding(padding),
            )

            else -> Unit
        }
    }
}

@Composable
private fun SessionDetailContent(session: SessionOut, modifier: Modifier = Modifier) {
    val weightUnit = LocalWeightUnit.current
    val pulse = SpotterTheme.pulse
    val spacing = SpotterTheme.spacing
    val completed = session.setLogs.filter { it.completed }
    val volumeLb = completed.sumOf { it.reps * (it.weight ?: 0.0) }
    // Preserve workout order: group consecutive sets by exercise the way they were performed.
    val byExercise = session.setLogs.groupBy { it.exerciseId }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            PanelCard(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    HeaderStat(
                        label = "DURATION",
                        value = session.durationSeconds?.let { formatDuration(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    HeaderStat(
                        label = "SETS",
                        value = "${completed.size}/${session.setLogs.size}",
                        modifier = Modifier.weight(1f),
                    )
                    HeaderStat(
                        label = "VOLUME",
                        value = if (volumeLb > 0) weightUnit.formatWeight(volumeLb) else "—",
                        modifier = Modifier.weight(1f),
                    )
                }
                session.routineName?.let { name ->
                    Spacer(Modifier.height(spacing.sm))
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                session.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(Modifier.height(spacing.sm))
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        items(byExercise.entries.toList(), key = { it.key }) { (exerciseId, sets) ->
            ExerciseDetailCard(
                sets = sets,
                note = session.exerciseNotes?.get(exerciseId),
            )
        }

        if (session.muscleGroups.isNotEmpty()) {
            item { SectionHeader(label = "Muscle groups", channel = pulse.strength) }
            item {
                PanelCard(modifier = Modifier.fillMaxWidth()) {
                    session.muscleGroups.forEach { mg ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                mg.muscleGroup.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            DataText(
                                text = "${mg.sets} set${if (mg.sets != 1) "s" else ""}",
                                style = SpotterTheme.dataType.numeral,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        DataText(text = value, style = SpotterTheme.dataType.dataSmall)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExerciseDetailCard(sets: List<SetLogOut>, note: String?) {
    val weightUnit = LocalWeightUnit.current
    val pulse = SpotterTheme.pulse
    val first = sets.first()
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            first.exerciseName ?: "Exercise",
            style = MaterialTheme.typography.titleMedium,
        )
        note?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(SpotterTheme.spacing.sm))
        sets.sortedBy { it.setNumber }.forEach { set ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DataText(
                    text = "${set.setNumber}",
                    style = SpotterTheme.dataType.numeral,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp),
                )
                DataText(
                    text = set.weight?.let { "${set.reps} × ${weightUnit.formatWeight(it)}" }
                        ?: "${set.reps} reps",
                    style = SpotterTheme.dataType.numeral,
                    modifier = Modifier.weight(1f),
                    color = if (set.completed) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (set.completed) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Completed",
                        tint = pulse.recovery,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(
                        "skipped",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
