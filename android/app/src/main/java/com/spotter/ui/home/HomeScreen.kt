package com.spotter.ui.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.ui.components.ConfettiHost
import com.spotter.ui.components.ErrorState
import com.spotter.ui.components.ExercisePreviewRow
import com.spotter.ui.components.LoadingState
import com.spotter.ui.components.PanelCard
import com.spotter.ui.components.PulseButton
import com.spotter.ui.components.PulsingDots
import com.spotter.ui.components.SectionHeader
import com.spotter.ui.components.StatTile
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.formatWeight
import com.spotter.ui.theme.formatWeightFieldLabel
import com.spotter.util.UiState
import com.spotter.util.UpcomingWorkout
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val routines by viewModel.routines.collectAsState()
    val startState by viewModel.startState.collectAsState()
    val generatingPlan by viewModel.generatingPlan.collectAsState()
    val streak by viewModel.streak.collectAsState()
    val weeklyActiveMinutes by viewModel.weeklyActiveMinutes.collectAsState()
    val weeklyMinutesByDay by viewModel.weeklyMinutesByDay.collectAsState()
    val upcoming by viewModel.upcoming.collectAsState()
    val activeProgramId by viewModel.activeProgramId.collectAsState()
    val greeting by viewModel.greeting.collectAsState()
    val bodyweight by viewModel.bodyweight.collectAsState()
    val routineExercises by viewModel.routineExercises.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val isStarting = startState is UiState.Loading
    var showBodyweightDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val spacing = SpotterTheme.spacing

    LaunchedEffect(actionError) {
        actionError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionError()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToWorkout.collect { sessionId ->
            navController.navigate(Screen.Workout.createRoute(sessionId))
        }
    }

    // Refresh stats/upcoming whenever Home returns to the foreground so finishing a
    // workout immediately updates the streak, active minutes, and upcoming blocks.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showBodyweightDialog) {
        BodyweightLogDialog(
            onDismiss = { showBodyweightDialog = false },
            onConfirm = { weight ->
                viewModel.logBodyweight(weight)
                showBodyweightDialog = false
            },
        )
    }

    // Celebrate when the streak crosses a milestone — once per milestone value per session.
    var celebratedStreak by rememberSaveable { mutableStateOf(0) }
    var celebrateStreak by remember { mutableStateOf(false) }
    LaunchedEffect(streak) {
        if (isStreakMilestone(streak) && celebratedStreak != streak) {
            celebratedStreak = streak
            celebrateStreak = true
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("Spotter", style = MaterialTheme.typography.titleLarge) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                    actions = {
                        IconButton(onClick = { navController.navigate(Screen.CreateRoutine.route) }) {
                            Icon(Icons.Default.Add, contentDescription = "New routine")
                        }
                        IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
        ) { padding ->
            when (val state = routines) {
                is UiState.Loading -> LoadingState(Modifier.padding(padding))

                is UiState.Error -> ErrorState(
                    message = state.message,
                    modifier = Modifier.padding(padding),
                )

                is UiState.Success -> {
                    val upcomingList = (upcoming as? UiState.Success)?.data.orEmpty()
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentPadding = PaddingValues(spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(spacing.md),
                    ) {
                        item {
                            GreetingPanel(
                                greeting = greeting,
                                nextWorkout = upcomingList.firstOrNull(),
                            )
                        }
                        item {
                            StatsBand(
                                streak = streak,
                                streakMilestone = isStreakMilestone(streak),
                                weeklyActiveMinutes = weeklyActiveMinutes,
                                weeklyMinutesByDay = weeklyMinutesByDay,
                                bodyweight = bodyweight,
                                onLogBodyweight = { showBodyweightDialog = true },
                            )
                        }

                        if (upcomingList.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "Upcoming",
                                    trailing = {
                                        TextButton(onClick = { navController.navigate(Screen.Programs.route) }) {
                                            Text(
                                                "Programs",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = SpotterTheme.pulse.effort,
                                            )
                                        }
                                    },
                                )
                            }
                            items(upcomingList, key = { "${it.date}-${it.routineId}-${it.dayLabel}" }) { workout ->
                                UpcomingWorkoutCard(
                                    workout = workout,
                                    isStarting = isStarting,
                                    onStart = { workout.routineId?.let { viewModel.startSession(it) } },
                                    onTapCard = activeProgramId?.let { pid ->
                                        { navController.navigate(Screen.ProgramDetail.createRoute(pid)) }
                                    },
                                )
                            }
                        }

                        when {
                            state.data.isEmpty() && !generatingPlan -> item {
                                EmptyPlansPrompt(onChat = { navController.navigate(Screen.AiChat.createRoute()) })
                            }

                            generatingPlan && state.data.isEmpty() -> item {
                                GeneratingPlaceholder()
                            }

                            else -> {
                                item { SectionHeader("Your routines", channel = SpotterTheme.pulse.strength) }
                                items(state.data, key = { it.id }) { routine ->
                                    RoutineCard(
                                        routine = routine,
                                        exercises = routineExercises[routine.id].orEmpty(),
                                        isStarting = isStarting,
                                        onStart = { viewModel.startSession(routine.id) },
                                        onDelete = { viewModel.deleteRoutine(routine.id) },
                                        onRename = { newName -> viewModel.renameRoutine(routine.id, newName) },
                                        onTapCard = { navController.navigate(Screen.RoutineDetail.createRoute(routine.id)) },
                                    )
                                }
                            }
                        }
                    }
                }

                else -> Unit
            }
        }
        ConfettiHost(play = celebrateStreak)
    }
}

/** "Today" for today, the weekday name within the coming week, else a dated label. */
private fun formatUpcomingDate(date: LocalDate): String {
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date.isAfter(today) && date.isBefore(today.plusDays(7)) ->
            date.format(DateTimeFormatter.ofPattern("EEEE"))
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    }
}

/** Day-streak values worth a confetti moment. */
private fun isStreakMilestone(streak: Int): Boolean =
    streak in setOf(3, 7, 14, 30, 50, 75, 100, 150, 200, 250, 300, 365) ||
        (streak >= 100 && streak % 100 == 0)

/** The greeting panel: a quiet headline plus a one-line status of what's next. */
@Composable
private fun GreetingPanel(greeting: String, nextWorkout: UpcomingWorkout?) {
    PanelCard(modifier = Modifier.fillMaxWidth(), contentPadding = 20.dp) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(SpotterTheme.spacing.xs))
        val status = if (nextWorkout != null) {
            val name = nextWorkout.routineName ?: nextWorkout.dayLabel
            "Next up: $name · ${formatUpcomingDate(nextWorkout.date)}"
        } else {
            "No workout scheduled — your coach can fix that."
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsBand(
    streak: Int,
    streakMilestone: Boolean,
    weeklyActiveMinutes: Int,
    weeklyMinutesByDay: List<Float>,
    bodyweight: Double?,
    onLogBodyweight: () -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    val pulse = SpotterTheme.pulse
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
    ) {
        StatTile(
            modifier = Modifier.weight(1f),
            animatedValue = streak,
            label = "day streak",
            accent = if (streak > 0 || streakMilestone) pulse.streak else null,
        )
        StatTile(
            modifier = Modifier.weight(1f),
            animatedValue = weeklyActiveMinutes,
            label = "active min",
            accent = pulse.effort,
            sparkline = weeklyMinutesByDay.takeIf { week -> week.any { it > 0f } },
        )
        StatTile(
            modifier = Modifier.weight(1f),
            value = bodyweight?.let { weightUnit.formatWeight(it) } ?: "—",
            label = "bodyweight",
            onClick = onLogBodyweight,
        )
    }
}

@Composable
private fun UpcomingWorkoutCard(
    workout: UpcomingWorkout,
    isStarting: Boolean,
    onStart: () -> Unit,
    onTapCard: (() -> Unit)? = null,
) {
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onTapCard,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatUpcomingDate(workout.date).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = SpotterTheme.pulse.effort,
                )
                Text(
                    text = workout.routineName ?: workout.dayLabel,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (workout.routineId != null) {
                PulseButton(
                    text = if (isStarting) "Starting…" else "Start",
                    onClick = onStart,
                    enabled = !isStarting,
                    tonal = true,
                    compact = true,
                )
            }
        }
        if (workout.lifts.isNotEmpty()) {
            Spacer(Modifier.height(SpotterTheme.spacing.sm))
            workout.lifts.forEach { lift ->
                ExercisePreviewRow(lift)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun EmptyPlansPrompt(onChat: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SpotterTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(SpotterTheme.pulse.effortDim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = SpotterTheme.pulse.effort,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(SpotterTheme.spacing.lg))
        Text("No workout routines yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(SpotterTheme.spacing.xs))
        Text(
            "Your AI Coach can build your first one in seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(SpotterTheme.spacing.xl))
        PulseButton(text = "Chat with AI Coach", onClick = onChat)
    }
}

@Composable
private fun GeneratingPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SpotterTheme.spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulsingDots()
        Spacer(Modifier.height(SpotterTheme.spacing.lg))
        Text("Building your first routine…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RoutineCard(
    routine: WorkoutRoutineEntity,
    exercises: List<RoutineExerciseEntity>,
    isStarting: Boolean,
    onStart: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
    onTapCard: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        RenameDialog(
            currentName = routine.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                onRename(newName)
                showRenameDialog = false
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete routine?") },
            text = { Text("\"${routine.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }

    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onTapCard,
        contentPadding = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                start = SpotterTheme.spacing.lg,
                top = SpotterTheme.spacing.md,
                bottom = SpotterTheme.spacing.md,
                end = SpotterTheme.spacing.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(routine.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    routine.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PulseButton(
                text = if (isStarting) "Starting…" else "Start",
                onClick = onStart,
                enabled = !isStarting,
                tonal = true,
                compact = true,
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Routine options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = { menuExpanded = false; showRenameDialog = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        onClick = { menuExpanded = false; showDeleteConfirm = true },
                    )
                }
            }
        }
        if (exercises.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(
                    start = SpotterTheme.spacing.lg,
                    end = SpotterTheme.spacing.lg,
                    bottom = SpotterTheme.spacing.md,
                ),
            ) {
                exercises.forEach { lift ->
                    ExercisePreviewRow(lift)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var nameText by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename routine") },
        text = {
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                label = { Text("Routine name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(nameText) },
                enabled = nameText.isNotBlank(),
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun BodyweightLogDialog(
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    var weightText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log bodyweight") },
        text = {
            OutlinedTextField(
                value = weightText,
                onValueChange = { weightText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(weightUnit.formatWeightFieldLabel()) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { weightText.toDoubleOrNull()?.let { onConfirm(it) } },
                enabled = weightText.toDoubleOrNull() != null,
            ) { Text("Log") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
