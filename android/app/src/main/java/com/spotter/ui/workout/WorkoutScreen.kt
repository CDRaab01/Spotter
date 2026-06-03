package com.spotter.ui.workout

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.SetLogOut
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.formatWeight
import com.spotter.ui.theme.formatWeightLabel
import com.spotter.ui.theme.toDisplay
import com.spotter.util.UiState
import com.spotter.util.WeightUnit

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
    val workSeconds by viewModel.workSeconds.collectAsState()
    val exerciseNotes by viewModel.exerciseNotes.collectAsState()
    val priorBests by viewModel.priorBests.collectAsState()
    val timerText = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    val isFinishing = finishState is UiState.Loading

    var showFinishDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(restTimerSeconds) {
        if (restTimerSeconds == 0) {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 300, 150, 300), -1)
                }
            }
        }
    }

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

            // Always-on work / rest timer. Counts up ("Working") between sets and flips
            // to a "Rest" countdown right after a set is completed.
            AnimatedVisibility(visible = totalCount > 0) {
                RestTimerCard(
                    restTimerSeconds = restTimerSeconds,
                    workSeconds = workSeconds,
                    onSkip = { viewModel.dismissRestTimer() },
                )
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
                                    onCommitValues = { setLog, reps, weight ->
                                        viewModel.editSet(sessionId, setLog, reps, weight)
                                    },
                                    onToggleComplete = { setLog, reps, weight ->
                                        viewModel.toggleComplete(sessionId, setLog, reps, weight)
                                    },
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

/**
 * The work/rest timer. While resting it shows a countdown inside a circular ring that drains as
 * the rest elapses, with a gentle breathing pulse; while working it's a calm counting-up clock.
 */
@Composable
private fun RestTimerCard(
    restTimerSeconds: Int?,
    workSeconds: Int,
    onSkip: () -> Unit,
) {
    val resting = restTimerSeconds != null

    // Remember the rest length the countdown started from so the ring can show progress without
    // the ViewModel exposing it. Resets whenever a new (longer) rest begins.
    var restStart by remember { mutableStateOf(1) }
    LaunchedEffect(restTimerSeconds) {
        val s = restTimerSeconds
        if (s != null && s > restStart) restStart = s
        if (s == null) restStart = 1
    }
    val ringProgress = if (resting && restStart > 0) {
        (restTimerSeconds ?: 0).toFloat() / restStart
    } else 0f

    // Breathing pulse while resting.
    val pulse = rememberInfiniteTransition(label = "restPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (resting) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    val container by animateColorAsState(
        targetValue = if (resting) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceVariant,
        label = "timerContainer",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val seconds = if (resting) (restTimerSeconds ?: 0) else workSeconds
            Box(
                modifier = Modifier.size(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (resting) {
                    CircularProgressIndicator(
                        progress = { ringProgress },
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(pulseScale),
                        strokeWidth = 5.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    )
                }
                Text(
                    text = "%d:%02d".format(seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = if (resting) "Rest — next set coming up." else "Working",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (resting) {
                IconButton(onClick = onSkip) {
                    Icon(Icons.Default.Close, contentDescription = "Skip rest")
                }
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    sets: List<SetLogOut>,
    note: String,
    priorBest: ExercisePrior?,
    onCommitValues: (SetLogOut, reps: Int, weightLbs: Double?) -> Unit,
    onToggleComplete: (SetLogOut, reps: Int, weightLbs: Double?) -> Unit,
    onAddSet: (SetLogOut) -> Unit,
    onNoteSave: (String) -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    val first = sets.first()
    val name = first.exerciseName ?: first.exerciseId
    val targetHeader = buildTargetHeader(first, weightUnit)
    val done = sets.count { it.completed }
    val supersetGroup = first.supersetGroup
    var showNote by remember { mutableStateOf(note.isNotEmpty()) }
    var noteText by remember(note) { mutableStateOf(note) }
    val focusManager = LocalFocusManager.current

    // Weight to warm up into: planned target, else the AI suggestion / last load.
    val workingWeight = first.targetWeight
        ?: priorBest?.suggestedWeight
        ?: priorBest?.weight
    var showWarmUp by remember { mutableStateOf(false) }
    if (showWarmUp && workingWeight != null) {
        WarmUpDialog(workingWeightLbs = workingWeight, onDismiss = { showWarmUp = false })
    }
    var showPlateCalc by remember { mutableStateOf(false) }
    if (showPlateCalc) {
        PlateCalculatorDialog(
            initialWeight = weightUnit.toDisplay(workingWeight ?: 0.0).toFloat(),
            weightUnit = weightUnit,
            onDismiss = { showPlateCalc = false },
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (supersetGroup != null) {
                Text(
                    text = "Superset ${('A' + supersetGroup - 1).uppercaseChar()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
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
                        if (priorBest.lastSets.isNotEmpty()) {
                            val lastSetsText = priorBest.lastSets.joinToString(" · ") { sl ->
                                val wt = sl.weight
                                if (wt != null) "${sl.reps}×${weightUnit.formatWeight(wt)}"
                                else "${sl.reps} reps"
                            }
                            Text(
                                "Last: $lastSetsText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        } else {
                            val weightStr = priorBest.weight?.let { " @ ${weightUnit.formatWeight(it)}" } ?: ""
                            Text(
                                "Best: ${priorBest.reps} reps$weightStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        priorBest.suggestedWeight?.let { suggested ->
                            Text(
                                "Suggested: ${weightUnit.formatWeight(suggested)}" +
                                    (priorBest.suggestedReason?.let { " — $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
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
            // Column header: aligns with each set's [N] [reps] [weight] [✓] row.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Set",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(36.dp),
                )
                Text(
                    "Reps",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(76.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    weightUnit.formatWeightLabel().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(96.dp),
                )
            }
            sets.forEach { setLog ->
                SetLogRow(
                    setLog = setLog,
                    onCommit = { reps, weight -> onCommitValues(setLog, reps, weight) },
                    onToggleComplete = { reps, weight -> onToggleComplete(setLog, reps, weight) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (workingWeight != null && workingWeight > 0) {
                    TextButton(onClick = { showPlateCalc = true }) {
                        Text("Plates")
                    }
                    TextButton(onClick = { showWarmUp = true }) {
                        Text("Warm-up")
                    }
                }
                TextButton(onClick = { onAddSet(sets.last()) }) {
                    Text("+ Add Set")
                }
            }
        }
    }
}

private fun buildTargetHeader(set: SetLogOut, weightUnit: WeightUnit): String {
    val targetSets = set.targetSets ?: return ""
    val targetReps = set.targetReps ?: return ""
    return if (set.targetWeight == null) {
        "$targetSets × $targetReps  BW"
    } else {
        "$targetSets × $targetReps @ ${weightUnit.formatWeight(set.targetWeight)}"
    }
}
