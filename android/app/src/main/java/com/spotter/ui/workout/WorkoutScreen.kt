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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.spotter.data.model.EquipmentInventory
import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.SetLogOut
import design.pulse.ui.components.DataText
import com.spotter.ui.components.LoadingState
import com.spotter.ui.components.SupersetContainer
import com.spotter.ui.components.SupersetGrouping
import com.spotter.ui.components.SupersetPositionTag
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.ProgressRing
import design.pulse.ui.components.PulseButton
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalWeightUnit
import design.pulse.ui.theme.PulseMotion
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.formatWeight
import com.spotter.ui.theme.formatWeightLabel
import com.spotter.util.ExerciseLoad
import com.spotter.util.Loading
import com.spotter.util.UiState
import com.spotter.util.WeightUnit
import kotlinx.coroutines.delay

/** Lets the rest ring finish opening above the list before the current set is revealed. */
private const val REVEAL_SETTLE_MS = 350L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val trackRpe by viewModel.trackRpe.collectAsState()
    val pendingRestDuration by viewModel.pendingRestDuration.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val inventory by viewModel.inventory.collectAsState()
    val exerciseLoads by viewModel.exerciseLoads.collectAsState()
    val timerText = formatElapsed(elapsed)
    val isFinishing = finishState is UiState.Loading

    var showFinishDialog by remember { mutableStateOf(false) }
    var showAddExercise by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(actionError) {
        actionError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionError()
        }
    }

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
                    data.durationSeconds, data.doneSets, data.totalSets, data.totalVolumeLb, data.newPrCount,
                    // Carries the just-finished session so the summary can ask the coach for a
                    // debrief; the summary renders fully without it.
                    sessionId = sessionId,
                )
            ) { popUpTo(Screen.Workout.route) { inclusive = true } }
        }
    }

    val allSets = (session as? UiState.Success)?.data?.setLogs ?: emptyList()

    // Fold consecutive exercises that share a superset group into one bracketed block
    // (A1/A2 with shared rest); everything else stays a standalone card. One block = one list item.
    val blocks = remember(allSets) {
        SupersetGrouping.group(allSets.groupBy { it.exerciseId }.entries.toList()) {
            it.value.firstOrNull()?.supersetGroup
        }
    }

    // Keep the set you're on in view (WorkoutAutoScroll): straight to it when the workout opens,
    // glide to the next exercise when one is finished, and nudge the next set up past the rest
    // ring. Saveable, so rotation or coming back from the coach doesn't re-jump.
    val listState = rememberLazyListState()
    val currentRowRequester = remember { BringIntoViewRequester() }
    val current = remember(blocks) {
        WorkoutAutoScroll.currentSet(blocks.map { block -> block.items.map { it.value } })
    }
    var scrolledBlock by rememberSaveable { mutableStateOf<Int?>(null) }
    var scrolledSetId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        val target = current ?: return@LaunchedEffect
        val move = WorkoutAutoScroll.move(scrolledBlock, scrolledSetId, target)
        scrolledBlock = target.blockIndex
        scrolledSetId = target.setId
        when (move) {
            WorkoutAutoScroll.Move.JUMP -> listState.scrollToItem(target.blockIndex)
            WorkoutAutoScroll.Move.ANIMATE -> listState.animateScrollToItem(target.blockIndex)
            else -> Unit
        }
        if (move != WorkoutAutoScroll.Move.NONE) {
            delay(REVEAL_SETTLE_MS)
            currentRowRequester.bringIntoView()
        }
    }

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

    if (showAddExercise) {
        val query by viewModel.exerciseSearchQuery.collectAsState()
        val results by viewModel.exerciseSearchResults.collectAsState()
        AddExerciseDialog(
            query = query,
            results = results,
            onQueryChange = { viewModel.exerciseSearchQuery.value = it },
            onPick = { exercise ->
                showAddExercise = false
                viewModel.exerciseSearchQuery.value = ""
                viewModel.addExercise(sessionId, exercise)
            },
            onDismiss = {
                showAddExercise = false
                viewModel.exerciseSearchQuery.value = ""
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    pendingRestDuration = pendingRestDuration,
                    onSkip = { viewModel.dismissRestTimer() },
                    onAdjust = { viewModel.adjustRest(it) },
                    onStartPendingRest = { viewModel.startPendingRest() },
                )
            }

            when (val state = session) {
                is UiState.Loading -> LoadingState()

                is UiState.Error -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { Text(state.message, color = MaterialTheme.colorScheme.error) }

                is UiState.Success -> {
                    if (state.data.setLogs.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "No exercises yet.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(SpotterTheme.spacing.md))
                                PulseButton(
                                    text = "+ Add exercise",
                                    onClick = { showAddExercise = true },
                                    tonal = true,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = listState,
                            contentPadding = PaddingValues(SpotterTheme.spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.md),
                        ) {
                            items(blocks, key = { it.items.first().key }) { block ->
                                @Composable
                                fun card(exerciseId: String, sets: List<SetLogOut>, positionLabel: String?) {
                                    ExerciseCard(
                                        sets = sets,
                                        note = exerciseNotes[exerciseId] ?: "",
                                        priorBest = priorBests[exerciseId],
                                        positionLabel = positionLabel,
                                        trackRpe = trackRpe,
                                        inventory = inventory,
                                        load = exerciseLoads[exerciseId],
                                        currentSetId = current?.setId,
                                        currentRowModifier = Modifier.bringIntoViewRequester(currentRowRequester),
                                        onCommitValues = { setLog, reps, weight ->
                                            viewModel.editSet(sessionId, setLog, reps, weight)
                                        },
                                        onToggleComplete = { setLog, reps, weight ->
                                            viewModel.toggleComplete(sessionId, setLog, reps, weight)
                                        },
                                        onAddSet = { lastSet -> viewModel.addSet(sessionId, exerciseId, lastSet) },
                                        onNoteSave = { note -> viewModel.saveExerciseNote(sessionId, exerciseId, note) },
                                        onApplySuggestion = {
                                            priorBests[exerciseId]?.let { viewModel.applyProgression(sessionId, it) }
                                        },
                                        onSetType = { setLog, type -> viewModel.setSetType(sessionId, setLog, type) },
                                        onDeleteSet = { setLog -> viewModel.deleteSet(sessionId, setLog) },
                                        onRpeCommit = { setLog, rpe -> viewModel.setRpe(sessionId, setLog, rpe) },
                                        onRemoveExercise = { viewModel.removeExercise(sessionId, exerciseId) },
                                        onOpenExercise = {
                                            navController.navigate(Screen.ExerciseDetail.createRoute(exerciseId))
                                        },
                                    )
                                }
                                when (block) {
                                    is SupersetGrouping.Single -> {
                                        val (exerciseId, sets) = block.item
                                        card(exerciseId, sets, positionLabel = null)
                                    }
                                    is SupersetGrouping.Superset -> {
                                        SupersetContainer(
                                            groupLabel = SupersetGrouping.groupLabel(block.group),
                                        ) {
                                            block.items.forEachIndexed { idx, (exerciseId, sets) ->
                                                card(
                                                    exerciseId,
                                                    sets,
                                                    SupersetGrouping.positionLabel(block.group, idx),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            item(key = "add-exercise") {
                                PulseButton(
                                    text = "+ Add exercise",
                                    onClick = { showAddExercise = true },
                                    tonal = true,
                                    modifier = Modifier.fillMaxWidth(),
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
 * countdown, mono readout in the middle, ±15s nudges, and a skip control. Working: a slim
 * strip with the count-up in effort cyan — plus a "Start rest" button when auto-start is off
 * and a completed set has queued one ([pendingRestDuration]).
 */
@Composable
private fun RestInstrumentPanel(
    restTimerSeconds: Int?,
    restDurationSeconds: Int?,
    workSeconds: Int,
    pendingRestDuration: Int?,
    onSkip: () -> Unit,
    onAdjust: (Int) -> Unit,
    onStartPendingRest: () -> Unit,
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseButton(
                            text = "−15s",
                            onClick = { onAdjust(-15) },
                            tonal = true,
                            compact = true,
                            channel = pulse.recovery,
                            onChannel = pulse.onRecovery,
                            dimChannel = pulse.recoveryDim,
                        )
                        Spacer(Modifier.width(SpotterTheme.spacing.sm))
                        PulseButton(
                            text = "Skip rest",
                            onClick = onSkip,
                            tonal = true,
                            compact = true,
                            channel = pulse.recovery,
                            onChannel = pulse.onRecovery,
                            dimChannel = pulse.recoveryDim,
                        )
                        Spacer(Modifier.width(SpotterTheme.spacing.sm))
                        PulseButton(
                            text = "+15s",
                            onClick = { onAdjust(15) },
                            tonal = true,
                            compact = true,
                            channel = pulse.recovery,
                            onChannel = pulse.onRecovery,
                            dimChannel = pulse.recoveryDim,
                        )
                    }
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
                    if (pendingRestDuration != null) {
                        PulseButton(
                            text = "Start rest",
                            onClick = onStartPendingRest,
                            tonal = true,
                            compact = true,
                            channel = pulse.recovery,
                            onChannel = pulse.onRecovery,
                            dimChannel = pulse.recoveryDim,
                        )
                    }
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
    positionLabel: String? = null,
    trackRpe: Boolean = false,
    inventory: EquipmentInventory = Loading.DEFAULT_LB,
    load: ExerciseLoad? = null,
    currentSetId: String? = null,
    currentRowModifier: Modifier = Modifier,
    onCommitValues: (SetLogOut, reps: Int, weightLbs: Double?) -> Unit,
    onToggleComplete: (SetLogOut, reps: Int, weightLbs: Double?) -> Unit,
    onAddSet: (SetLogOut) -> Unit,
    onNoteSave: (String) -> Unit,
    onApplySuggestion: (() -> Unit)? = null,
    onSetType: (SetLogOut, String) -> Unit = { _, _ -> },
    onDeleteSet: (SetLogOut) -> Unit = {},
    onRpeCommit: (SetLogOut, Double?) -> Unit = { _, _ -> },
    onRemoveExercise: () -> Unit = {},
    onOpenExercise: (() -> Unit)? = null,
) {
    val weightUnit = LocalWeightUnit.current
    val pulse = SpotterTheme.pulse
    val first = sets.first()
    val name = first.exerciseName ?: first.exerciseId
    val targetHeader = buildTargetHeader(first, weightUnit)
    val done = sets.count { it.completed }
    var showNote by remember { mutableStateOf(note.isNotEmpty()) }
    var noteText by remember(note) { mutableStateOf(note) }
    val focusManager = LocalFocusManager.current

    // Weight to warm up into: the seeded set load (the server may have lifted it above the
    // routine prescription from related-lift history), else the prescription, else the
    // AI suggestion / last load.
    val workingWeight = first.weight
        ?: first.targetWeight
        ?: priorBest?.suggestedWeight
        ?: priorBest?.weight
    var showWarmUp by remember { mutableStateOf(false) }
    if (showWarmUp && workingWeight != null) {
        WarmUpDialog(
            workingWeightLbs = workingWeight,
            onDismiss = { showWarmUp = false },
            ladder = load?.ladder,
        )
    }
    var showPlateCalc by remember { mutableStateOf(false) }
    if (showPlateCalc) {
        PlateCalculatorDialog(
            initialWeightLbs = workingWeight ?: 0.0,
            inventory = inventory,
            onDismiss = { showPlateCalc = false },
            singleSided = load?.mode == Loading.SINGLE,
        )
    }
    // Plates mean nothing for dumbbells or a pin-loaded stack; unknown equipment keeps the button.
    val platesApply = load?.mode != Loading.DUMBBELL && load?.mode != Loading.STACK
    // Set-type picker (also hosts deletion): opened from a row's set-number cell.
    var typePickerFor by remember { mutableStateOf<SetLogOut?>(null) }
    typePickerFor?.let { picked ->
        SetTypeDialog(
            setLog = picked,
            canDelete = sets.size > 1, // every exercise keeps at least one set
            onSelectType = { type ->
                typePickerFor = null
                if (type != picked.setType) onSetType(picked, type)
            },
            onDelete = {
                typePickerFor = null
                onDeleteSet(picked)
            },
            onDismiss = { typePickerFor = null },
        )
    }
    var showMenu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove $name?") },
            text = {
                Text(
                    "Its remaining sets are removed from this workout. " +
                        "Completed sets stay — they're history.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemoveExercise() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }

    PanelCard(modifier = Modifier.fillMaxWidth()) {
        if (positionLabel != null) {
            SupersetPositionTag(positionLabel)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = if (onOpenExercise != null) {
                        Modifier.clickable(onClick = onOpenExercise)
                    } else {
                        Modifier
                    },
                )
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
                    val prog = progressionUi(priorBest, weightUnit::formatWeight)
                    prog.suggestionText?.let { txt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                txt,
                                style = MaterialTheme.typography.bodySmall,
                                // Deload reads as a caution (amber), everything else as an action (blue).
                                color = if (prog.isDeload) pulse.streak else pulse.effort,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (prog.showPr) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "PR",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = pulse.strength,
                                )
                            }
                            // One-tap apply: incomplete sets take the suggested load now and the
                            // routine's target advances — without it, presets pre-fill the same
                            // starting weight forever and the suggestion is read-only advice.
                            val suggested = priorBest.suggestedWeight
                            val canApply = onApplySuggestion != null &&
                                suggested != null &&
                                sets.any { !it.completed && it.weight != suggested }
                            if (canApply) {
                                Spacer(Modifier.width(SpotterTheme.spacing.sm))
                                PulseButton(
                                    text = "Apply",
                                    onClick = { onApplySuggestion!!() },
                                    tonal = true,
                                    compact = true,
                                )
                            }
                        }
                    }
                    prog.e1rmText?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Exercise options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove exercise", color = MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; confirmRemove = true },
                    )
                }
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
                onOpenTypePicker = { typePickerFor = setLog },
                trackRpe = trackRpe,
                onRpeCommit = { rpe -> onRpeCommit(setLog, rpe) },
                modifier = if (setLog.id == currentSetId) currentRowModifier else Modifier,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            if (workingWeight != null && workingWeight > 0) {
                if (platesApply) {
                    TextButton(onClick = { showPlateCalc = true }) {
                        Text("Plates")
                    }
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

/**
 * The card's target line. The load shown is the first set's seeded weight when the routine
 * prescribes one — the server may seed above the prescription from related-lift history
 * (ARCHITECTURE.md invariant #8), and the header must describe the session, not the routine.
 * A null prescription still reads as bodyweight even if a set carries a weight.
 */
internal fun buildTargetHeader(set: SetLogOut, weightUnit: WeightUnit): String {
    val targetSets = set.targetSets ?: return ""
    val targetReps = set.targetReps ?: return ""
    return if (set.targetWeight == null) {
        "$targetSets × $targetReps  BW"
    } else {
        "$targetSets × $targetReps @ ${weightUnit.formatWeight(set.weight ?: set.targetWeight)}"
    }
}

/** Display pieces for the progressive-overload suggestion — pure so it's unit-testable without
 *  Compose. `formatWeight` is injected (the caller passes the unit-aware formatter). */
internal data class ProgressionUi(
    val suggestionText: String?,
    val isDeload: Boolean,
    val showPr: Boolean,
    val e1rmText: String?,
)

/** MM:SS under an hour, H:MM:SS beyond — a 75-minute session reads 1:15:00, not 75:00. */
internal fun formatElapsed(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * The mid-workout "Add exercise" picker: a debounced search over the catalog (mirror-backed, so
 * it works offline) — the CreateRoutine search pattern in dialog form. Picking adds the exercise
 * with three fresh sets.
 */
@Composable
private fun AddExerciseDialog(
    query: String,
    results: List<ExerciseOut>,
    onQueryChange: (String) -> Unit,
    onPick: (ExerciseOut) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add exercise") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Search exercises") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(SpotterTheme.spacing.sm))
                LazyColumn(modifier = Modifier.height(280.dp)) {
                    items(results, key = { it.id }) { exercise ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(exercise) }
                                .padding(
                                    vertical = SpotterTheme.spacing.sm,
                                    horizontal = SpotterTheme.spacing.xs,
                                ),
                        ) {
                            Text(exercise.name, style = MaterialTheme.typography.bodyLarge)
                            val subtitle = listOfNotNull(exercise.muscleGroup, exercise.equipment)
                                .joinToString(" · ")
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

internal fun progressionUi(p: ExercisePrior, formatWeight: (Double) -> String): ProgressionUi {
    val weight = p.suggestedWeight
    val reason = p.suggestedReason
    val text = when {
        weight != null -> "Suggested: ${formatWeight(weight)}" + (reason?.let { " — $it" } ?: "")
        reason != null -> reason // bodyweight / add-reps with no load
        else -> null
    }
    return ProgressionUi(
        suggestionText = text,
        isDeload = p.action == "deload",
        showPr = p.isPr,
        e1rmText = p.e1rm?.let { "e1RM ~${formatWeight(it)}" },
    )
}
