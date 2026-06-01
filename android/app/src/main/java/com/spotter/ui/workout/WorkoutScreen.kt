package com.spotter.ui.workout

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.spotter.data.model.SetLogOut
import com.spotter.util.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutScreen(
    sessionId: String,
    navController: NavController,
    viewModel: WorkoutViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsState()
    val elapsed by viewModel.elapsedSeconds.collectAsState()
    val timerText = "%02d:%02d".format(elapsed / 60, elapsed % 60)

    LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Workout", style = MaterialTheme.typography.titleMedium)
                        Text(
                            timerText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val state = session) {
            is UiState.Loading -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is UiState.Error -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text(state.message, color = MaterialTheme.colorScheme.error) }

            is UiState.Success -> {
                val grouped = state.data.setLogs.groupBy { it.exerciseId }
                if (grouped.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No exercises yet.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(grouped.entries.toList(), key = { it.key }) { (exerciseId, sets) ->
                            ExerciseCard(
                                sets = sets,
                                onToggle = { setLog -> viewModel.toggleSet(sessionId, setLog) },
                                onAddSet = { lastSet -> viewModel.addSet(sessionId, exerciseId, lastSet) },
                            )
                        }
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun ExerciseCard(
    sets: List<SetLogOut>,
    onToggle: (SetLogOut) -> Unit,
    onAddSet: (SetLogOut) -> Unit,
) {
    val first = sets.first()
    val name = first.exerciseName ?: first.exerciseId
    val targetHeader = buildTargetHeader(first)
    val done = sets.count { it.completed }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium)
                    if (targetHeader.isNotEmpty()) {
                        Text(
                            targetHeader,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    "$done / ${sets.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (done == sets.size) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                sets.forEach { setLog ->
                    SetLogRow(setLog = setLog, onToggle = { onToggle(setLog) })
                }
            }
            TextButton(
                onClick = { onAddSet(sets.last()) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("+ Add Set")
            }
        }
    }
}

private fun buildTargetHeader(set: SetLogOut): String {
    val targetSets = set.targetSets ?: return ""
    val targetReps = set.targetReps ?: return ""
    return if (set.targetWeight == null) {
        "$targetSets × $targetReps  BW"
    } else {
        "$targetSets × $targetReps @ ${set.targetWeight.toInt()} lb"
    }
}
