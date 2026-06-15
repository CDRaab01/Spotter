package com.spotter.ui.workout

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.SetLogOut
import com.spotter.ui.components.DataText
import com.spotter.ui.components.LoadingState
import com.spotter.ui.components.PanelCard
import com.spotter.ui.components.ProgressRing
import com.spotter.ui.components.PulseButton
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.PulseMotion
import com.spotter.ui.theme.SpotterTheme
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
    val restDurationSeconds by viewModel.restDurationSeconds.collectAsState()
    val workSeconds by viewModel.workSeconds.collectAsState()
    val exerciseNotes by viewModel.exerciseNotes.collectAsState()
    val priorBests by viewModel.priorBests.collectAsState()
    val timerText = "%02d:%02d".format(elapsed / 60, elapsed % 60)
    val isFinishing = finishState is UiState.Loading

    var showFinishDialog by remember { mutableStateOf(false) }

    // The end-of-rest vibration is owned by WorkoutTimerController (which holds a wake lock and fires
    // even when the app is backgrounded / screen-off), so there's no foreground-only cue here.

    // Reload on every ON_RESUME (covers first entry and returning from the coach chat,
    // where a popBackStack wouldn't re-key a LaunchedEffect(sessionId)) so AI-applied
    // adjustments are reflected. loadSession guards against a spinner flash on re-resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sessionId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.loadSession(sessionId)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { navController.popBackStack() }
    }
    LaunchedEffect(Unit) {
        viewModel.navigateToSummary.collect { data ->
            navController.navigate(
                Screen.WorkoutSummary.createRoute(
                    data.durationSeconds, data.doneSets, data.totalSets, data.totalVolumeLb, data.newPrCount
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
                ) { Text("Finish", color = SpotterTheme.pulse.recovery) }
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
                        DataText(
                            text = timerText,
                            style = SpotterTheme.dataType.numeral,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        navController.navigate(Screen.AiChat.createRoute(sessionId))
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Ask the coach",
                        )
                    }
                    IconButton(
                        onClick = { showFinishDialog = true },
                        enabled = completedCount > 0 && !isFinishing,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Finish workout",
                            tint = if (completedCount > 0) SpotterTheme.pulse.recovery
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (totalCount > 0) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = SpotterTheme.spacing.lg,
                        vertical = SpotterTheme.spacing.xs,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (allDone) "ALL SETS COMPLETE" else "SETS",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (allDone) SpotterTheme.pulse.recovery
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DataText(
                            text = "$completedCount/$totalCount",
                            style = SpotterTheme.dataType.numeral,
                            color = if (allDone) SpotterTheme.pulse.recovery
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(SpotterTheme.spacing.xs))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = if (allDone) SpotterTheme.pulse.recovery else SpotterTheme.pulse.effort,
                        trackColor = SpotterTheme.pulse.hairline,
                    )
                }
            }

            // Always-on work / rest instrument. A prominent recovery ring while resting; a slim
            // effort count-up strip while working.
            AnimatedVisibility(visible = totalCount > 0) {
                RestInstrumentPanel(
                    restTimerSeconds = restTimerSeconds,
                    restDurationSeconds = restDurationSeconds,
                    workSeconds = workSeconds,
                    onSkip = { viewModel.dismissRestTimer() },
                )
            }

            when (val state = session) {
                is UiState.Loading -> LoadingState()

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
                            contentPadding = PaddingValues(SpotterTheme.spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.md),
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
 * The work/rest instrument. Resting: a prominent recovery-green ring draining with the
 * countdown, mono readout in the middle, and a skip control. Working: a slim strip with the
 * count-up in effort cyan.
 */
@Composable
private fun RestInstrumentPanel(
    restTimerSeconds: Int?,
    restDurationSeconds: Int?,
    workSeconds: Int,
    onSkip: () -> Unit,
) {
    val pulse = SpotterTheme.pulse
    val resting = restTimerSeconds != null
    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpotterTheme.spacing.lg, vertical = SpotterTheme.spacing.xs),
        channel = if (resting) pulse.recovery else null,
        contentPadding = 0.dp,
    ) {
        AnimatedContent(
            targetState = resting,
            transitionSpec = {
                fadeIn(PulseMotion.standard()) togetherWith fadeOut(PulseMotion.fast())
            },
            label = "restInstrument",
        ) { isResting ->
            if (isResting) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(SpotterTheme.spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val remaining = restTimerSeconds ?: 0
                    val duration = (restDurationSeconds ?: remaining).coerceAtLeast(1)
                    ProgressRing(
                        progress = remaining.toFloat() / duration,
                        channel = pulse.recovery,
                        strokeWidth = 8.dp,
                        modifier = Modifier.size(150.dp),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            DataText(
                                text = "%d:%02d".format(remaining / 60, remaining % 60),
                                style = SpotterTheme.dataType.dataLarge,
                                color = pulse.recovery,
                            )
                            Text(
                                text = "REST",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(SpotterTheme.spacing.sm))
                    PulseButton(
                        text = "Skip rest",
                        onClick = onSkip,
                        tonal = true,
                        compact = true,
                        channel = pulse.recovery,
                        onChannel = pulse.onRecovery,
                        dimChannel = pulse.recoveryDim,
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = SpotterTheme.spacing.lg,
                            vertical = SpotterTheme.spacing.md,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DataText(
                        text = "%d:%02d".format(workSeconds / 60, workSeconds % 60),
                        style = SpotterTheme.dataType.dataSmall,
                        color = pulse.effort,
                    )
                    Spacer(Modifier.width(SpotterTheme.spacing.md))
                    Text(
                        text = "WORKING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
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
    val pulse = SpotterTheme.pulse
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

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        if (supersetGroup != null) {
            Text(
                text = "SUPERSET ${('A' + supersetGroup - 1).uppercaseChar()}",
                style = MaterialTheme.typography.labelSmall,
                color = pulse.strength,
                modifier = Modifier.padding(bottom = SpotterTheme.spacing.xs),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (targetHeader.isNotEmpty()) {
                    DataText(
                        text = targetHeader,
                        style = SpotterTheme.dataType.numeral,
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
                            color = pulse.strength,
                        )
                    } else {
                        val weightStr = priorBest.weight?.let { " @ ${weightUnit.formatWeight(it)}" } ?: ""
                        Text(
                            "Best: ${priorBest.reps} reps$weightStr",
                            style = MaterialTheme.typography.bodySmall,
                            color = pulse.strength,
                        )
                    }
                    priorBest.suggestedWeight?.let { suggested ->
                        Text(
                            "Suggested: ${weightUnit.formatWeight(suggested)}" +
                                (priorBest.suggestedReason?.let { " — $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = pulse.effort,
                        )
                    }
                }
            }
            IconButton(onClick = { showNote = !showNote }) {
                Icon(
                    Icons.Default.EditNote,
                    contentDescription = "Toggle note",
                    tint = if (noteText.isNotEmpty()) pulse.effort
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DataText(
                text = "$done/${sets.size}",
                style = SpotterTheme.dataType.numeral,
                color = if (done == sets.size) pulse.recovery
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = showNote) {
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().padding(top = SpotterTheme.spacing.sm),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    onNoteSave(noteText)
                    focusManager.clearFocus()
                }),
                maxLines = 3,
            )
        }

        Spacer(Modifier.height(SpotterTheme.spacing.md))
        // Column header: aligns with each set's [N] [reps] [weight] [✓] row.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SET",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(36.dp),
            )
            Text(
                "REPS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(76.dp),
            )
            Spacer(Modifier.width(SpotterTheme.spacing.sm))
            Text(
                weightUnit.formatWeightLabel().uppercase(),
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
                Text("+ Add Set", color = pulse.effort)
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
