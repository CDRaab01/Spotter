package com.spotter.ui.workout

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.SetLogOut
import com.spotter.ui.navigation.Screen
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
    val finishState by viewModel.finishState.collectAsState()
    val restTimerSeconds by viewModel.restTimerSeconds.collectAsState()
    val exerciseNotes by viewModel.exerciseNotes.collectAsState()
    val priorBests by viewModel.priorBests.collectAsState()
    val timerText = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    val isFinishing = finishState is UiState.Loading

    var editingSet by remember { mutableStateOf<SetLogOut?>(null) }
    var showFinishDialog by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) { viewModel.loadSession(sessionId) }
    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { navController.popBackStack() }
    }
    LaunchedEffect(Unit) {
        viewModel.navigateToSummary.collect { data ->
            navController.navigate(
                Screen.WorkoutSummary.createRoute(
                    data.durationSeconds, data.doneSets, data.totalSets, data.totalVolumeLb
                )
            ) { popUpTo(Screen.Workout.route) { inclusive = true } }
        }
    }

    val allSets = (session as? UiState.Success)?.data?.setLogs ?: emptyList()
    val completedCount = allSets.count { it.completed }
    val totalCount = allSets.size
    val progress = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f
    val allDone = totalCount > 0 && completedCount == totalCount

    if (showFinishDialog) {
        AlertDialog(
            onDismissRequest = { showFinishDialog = false },
            title = { Text("Finish workout?") },
            text = { Text("$completedCount of $totalCount sets completed · $timerText") },
            confirmButton = {
                TextButton(
                    onClick = { showFinishDialog = false; viewModel.finishSession(sessionId) },
                    enabled = !isFinishing,
                ) { Text("Finish") }
            },
            dismissButton = {
                TextButton(onClick = { showFinishDialog = false }) { Text("Cancel") }
            },
        )
    }

    editingSet?.let { setLog ->
        EditSetDialog(
            setLog = setLog,
            onDismiss = { editingSet = null },
            onConfirm = { reps, weight ->
                viewModel.editSet(sessionId, setLog, reps, weight)
                editingSet = null
            },
        )
    }

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
                actions = {
                    IconButton(
                        onClick = { showFinishDialog = true },
                        enabled = completedCount > 0 && !isFinishing,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Finish workout")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (totalCount > 0) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = if (allDone) "All sets complete!" else "$completedCount / $totalCount sets",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (allDone) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Rest timer banner
            AnimatedVisibility(visible = restTimerSeconds != null) {
                restTimerSeconds?.let { seconds ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text(
                                    "Rest Timer",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    "%d:%02d".format(seconds / 60, seconds % 60),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            IconButton(onClick = { viewModel.dismissRestTimer() }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Dismiss timer",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }

            when (val state = session) {
                is UiState.Loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is UiState.Error -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text(state.message, color = MaterialTheme.colorScheme.error) }

                is UiState.Success -> {
                    val grouped = state.data.setLogs.groupBy { it.exerciseId }
                    if (grouped.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "No exercises yet.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(grouped.entries.toList(), key = { it.key }) { (exerciseId, sets) ->
                                ExerciseCard(
                                    sets = sets,
                                    note = exerciseNotes[exerciseId] ?: "",
                                    priorBest = priorBests[exerciseId],
                                    onToggle = { setLog -> viewModel.toggleSet(sessionId, setLog) },
                                    onEditWeight = { setLog -> editingSet = setLog },
                                    onAddSet = { lastSet -> viewModel.addSet(sessionId, exerciseId, lastSet) },
                                    onNoteSave = { note -> viewModel.saveExerciseNote(sessionId, exerciseId, note) },
                                )
                            }
                        }
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun EditSetDialog(
    setLog: SetLogOut,
    onDismiss: () -> Unit,
    onConfirm: (reps: Int, weight: Double?) -> Unit,
) {
    var repsText by remember { mutableStateOf(setLog.reps.toString()) }
    var weightText by remember {
        mutableStateOf(setLog.weight?.let { "%.0f".format(it) } ?: "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Set ${setLog.setNumber}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { repsText = it.filter { c -> c.isDigit() } },
                    label = { Text("Reps") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                if (setLog.weight != null || setLog.targetWeight != null) {
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { new ->
                            weightText = new.filter { c -> c.isDigit() || c == '.' }
                        },
                        label = { Text("Weight (lb)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val reps = repsText.toIntOrNull() ?: setLog.reps
                    val weight = weightText.toDoubleOrNull()
                    onConfirm(reps, weight)
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ExerciseCard(
    sets: List<SetLogOut>,
    note: String,
    priorBest: ExercisePrior?,
    onToggle: (SetLogOut) -> Unit,
    onEditWeight: (SetLogOut) -> Unit,
    onAddSet: (SetLogOut) -> Unit,
    onNoteSave: (String) -> Unit,
) {
    val first = sets.first()
    val name = first.exerciseName ?: first.exerciseId
    val targetHeader = buildTargetHeader(first)
    val done = sets.count { it.completed }
    var showNote by remember { mutableStateOf(note.isNotEmpty()) }
    var noteText by remember(note) { mutableStateOf(note) }
    val focusManager = LocalFocusManager.current

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
                    if (priorBest != null) {
                        val weightStr = priorBest.weight?.let { " @ ${it.toInt()} lb" } ?: ""
                        Text(
                            "Last: ${priorBest.reps} reps$weightStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                IconButton(onClick = { showNote = !showNote }) {
                    Icon(
                        Icons.Default.EditNote,
                        contentDescription = "Toggle note",
                        tint = if (noteText.isNotEmpty()) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "$done / ${sets.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (done == sets.size) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = showNote) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        onNoteSave(noteText)
                        focusManager.clearFocus()
                    }),
                    maxLines = 3,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                sets.forEach { setLog ->
                    SetLogRow(
                        setLog = setLog,
                        onToggle = { onToggle(setLog) },
                        onEditWeight = { onEditWeight(setLog) },
                    )
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
