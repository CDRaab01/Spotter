package com.spotter.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.ui.components.AnimatedCounter
import com.spotter.ui.components.ConfettiHost
import com.spotter.ui.components.EmptyState
import com.spotter.ui.components.ErrorState
import com.spotter.ui.components.ExercisePreviewRow
import com.spotter.ui.components.GradientButton
import com.spotter.ui.components.LoadingState
import com.spotter.ui.components.PulsingDots
import com.spotter.ui.components.SectionHeader
import com.spotter.ui.components.SpotterCard
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
    val generationError by viewModel.generationError.collectAsState()
    val streak by viewModel.streak.collectAsState()
    val weeklyActiveMinutes by viewModel.weeklyActiveMinutes.collectAsState()
    val upcoming by viewModel.upcoming.collectAsState()
    val activeProgramId by viewModel.activeProgramId.collectAsState()
    val greeting by viewModel.greeting.collectAsState()
    val bodyweight by viewModel.bodyweight.collectAsState()
    val routineExercises by viewModel.routineExercises.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val isStarting = startState is UiState.Loading
    var showBodyweightDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

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
                title = { Text("Spotter") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Calendar.route) }) {
                        Icon(Icons.Default.CalendarToday, contentDescription = "Calendar")
                    }
                    IconButton(onClick = { navController.navigate(Screen.Progress.route) }) {
                        Icon(Icons.Default.ShowChart, contentDescription = "Progress")
                    }
                    IconButton(onClick = { navController.navigate(Screen.CreateRoutine.route) }) {
                        Icon(Icons.Default.Add, contentDescription = "New routine")
                    }
                    var overflowExpanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { overflowExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("History") },
                                onClick = {
                                    overflowExpanded = false
                                    navController.navigate(Screen.SessionHistory.route)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Programs") },
                                onClick = {
                                    overflowExpanded = false
                                    navController.navigate(Screen.Programs.route)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Exercise Library") },
                                onClick = {
                                    overflowExpanded = false
                                    navController.navigate(Screen.ExerciseLibrary.route)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    overflowExpanded = false
                                    navController.navigate(Screen.Settings.route)
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmallFloatingActionButton(onClick = { showBodyweightDialog = true }) {
                    Icon(Icons.Default.MonitorWeight, contentDescription = "Log bodyweight")
                }
                FloatingActionButton(onClick = { navController.navigate(Screen.AiChat.createRoute()) }) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "AI Coach")
                }
            }
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
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { GreetingHeader(greeting, onClick = { navController.navigate(Screen.AiChat.createRoute()) }) }
                    item { StatsBand(streak = streak, weeklyActiveMinutes = weeklyActiveMinutes, bodyweight = bodyweight) }

                    if (upcomingList.isNotEmpty()) {
                        item { SectionHeader("Upcoming workouts") }
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
                        generatingPlan && state.data.isEmpty() -> item {
                            GeneratingPlaceholder()
                        }

                        generationError != null && state.data.isEmpty() -> item {
                            GenerationErrorPrompt(
                                message = generationError!!,
                                onRetry = { viewModel.retryInitialRoutine() },
                                onChat = { navController.navigate(Screen.AiChat.createRoute()) },
                            )
                        }

                        state.data.isEmpty() -> item {
                            EmptyPlansPrompt(onChat = { navController.navigate(Screen.AiChat.createRoute()) })
                        }

                        else -> {
                            item { SectionHeader("Your routines") }
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

@Composable
private fun GreetingHeader(greeting: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(SpotterTheme.brand.heroGradient)
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Column {
            Text(
                text = "LET'S TRAIN",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun StatsBand(streak: Int, weeklyActiveMinutes: Int, bodyweight: Double?) {
    val weightUnit = LocalWeightUnit.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StreakTile(
            streak = streak,
            milestone = isStreakMilestone(streak),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            modifier = Modifier.weight(1f),
            animatedValue = weeklyActiveMinutes,
            label = "active min",
        )
        if (bodyweight != null) {
            StatTile(
                modifier = Modifier.weight(1f),
                value = weightUnit.formatWeight(bodyweight),
                label = "bodyweight",
            )
        }
    }
}

/** Streak stat with a flame that animates in proportion to the streak length. */
@Composable
private fun StreakTile(streak: Int, milestone: Boolean, modifier: Modifier = Modifier) {
    SpotterCard(
        modifier = modifier,
        contentPadding = 14.dp,
        border = if (milestone) BorderStroke(2.dp, SpotterTheme.brand.streak) else null,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedCounter(
                    target = streak,
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (streak > 0) {
                    Text(" 🔥", style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(
                text = "day streak",
                style = MaterialTheme.typography.labelMedium,
                color = if (streak >= 7) SpotterTheme.brand.streak
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (streak >= 7) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun UpcomingWorkoutCard(
    workout: UpcomingWorkout,
    isStarting: Boolean,
    onStart: () -> Unit,
    onTapCard: (() -> Unit)? = null,
) {
    val cardModifier = if (onTapCard != null) {
        Modifier.fillMaxWidth().clickable(onClick = onTapCard)
    } else {
        Modifier.fillMaxWidth()
    }
    Card(modifier = cardModifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = formatUpcomingDate(workout.date),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = workout.routineName ?: workout.dayLabel,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (workout.routineId != null) {
                    Button(onClick = onStart, enabled = !isStarting) {
                        Text(if (isStarting) "Starting…" else "Start")
                    }
                }
            }
            if (workout.lifts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                workout.lifts.forEach { lift ->
                    ExercisePreviewRow(lift)
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyPlansPrompt(onChat: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("No workout routines yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your AI Coach can build your first one in seconds.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        GradientButton(text = "Chat with AI Coach", onClick = onChat)
    }
}

@Composable
private fun GeneratingPlaceholder() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulsingDots()
        Spacer(Modifier.height(16.dp))
        Text("Building your first routine…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GenerationErrorPrompt(
    message: String,
    onRetry: () -> Unit,
    onChat: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Setup didn't finish", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        GradientButton(text = "Try again", onClick = onRetry)
        TextButton(onClick = onChat) { Text("Chat with AI Coach instead") }
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.clickable { onTapCard() }) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
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
                Button(
                    onClick = onStart,
                    enabled = !isStarting,
                ) {
                    Text(if (isStarting) "Starting…" else "Start")
                }
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
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    exercises.forEach { lift ->
                        ExercisePreviewRow(lift)
                        Spacer(Modifier.height(2.dp))
                    }
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
