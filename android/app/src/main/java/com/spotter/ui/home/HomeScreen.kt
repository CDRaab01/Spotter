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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.spotter.data.local.entity.WorkoutProgramEntity
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
    val programs by viewModel.programs.collectAsState()
    val programDayCounts by viewModel.programDayCounts.collectAsState()
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
                            item { SectionHeader("Upcoming") }
                            items(upcomingList, key = { "${it.date}-${it.routineId}-${it.dayLabel}" }) { workout ->
                                val cardio = workout.cardio
                                UpcomingWorkoutCard(
                                    workout = workout,
                                    isStarting = isStarting,
                                    onStart = { workout.routineId?.let { viewModel.startSession(it) } },
                                    onTapCard = when {
                                        cardio != null -> {
                                            { navController.navigate(Screen.CardioOverview.createRoute(cardio.programId)) }
                                        }
                                        activeProgramId != null -> {
                                            { navController.navigate(Screen.ProgramDetail.createRoute(activeProgramId!!)) }
                                        }
                                        else -> null
                                    },
                                )
                            }
                        }

                        when {
                            programs.isEmpty() && state.data.isEmpty() && !generatingPlan -> item {
                                EmptyPlansPrompt(onChat = { navController.navigate(Screen.AiChat.createRoute()) })
                            }

                            generatingPlan && programs.isEmpty() -> item {
                                GeneratingPlaceholder()
                            }

                            programs.isNotEmpty() -> {
                                item {
                                    SectionHeader(
                                        title = "Your programs",
                                        channel = SpotterTheme.pulse.strength,
                                        trailing = {
                                            TextButton(onClick = { navController.navigate(Screen.Programs.route) }) {
                                                Text(
                                                    "Manage",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = SpotterTheme.pulse.effort,
                                                )
                                            }
                                        },
                                    )
                                }
                                items(programs, key = { it.id }) { program ->
                                    ProgramCard(
                                        program = program,
                                        dayCount = programDayCounts[program.id] ?: 0,
                                        onTap = { navController.navigate(Screen.ProgramDetail.createRoute(program.id)) },
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

/** The greeting hero: the blue→indigo signature gradient with a one-line status of what's next. */
@Composable
private fun GreetingPanel(greeting: String, nextWorkout: UpcomingWorkout?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(SpotterTheme.pulse.heroGradient)
            .padding(20.dp),
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
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
            color = Color.White.copy(alpha = 0.85f),
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
    val cardio = workout.cardio
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onTapCard,
        channel = if (cardio != null) SpotterTheme.pulse.recovery else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = formatUpcomingDate(workout.date).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (cardio != null) SpotterTheme.pulse.recovery else SpotterTheme.pulse.effort,
                )
                Text(
                    text = if (cardio != null) {
                        "${cardio.programName} · W${cardio.week} D${cardio.day}"
                    } else {
                        workout.routineName ?: workout.dayLabel
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (cardio != null) {
                    Text(
                        text = "Run · ${com.spotter.ui.cardio.CardioFormat.minutesLabel(cardio.totalDurationSec)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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

/** A program at a glance: name + day count; the whole card opens the program's breakdown. */
@Composable
private fun ProgramCard(
    program: WorkoutProgramEntity,
    dayCount: Int,
    onTap: () -> Unit,
) {
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onTap,
        channel = if (program.isActive) SpotterTheme.pulse.effort else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(program.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (dayCount > 0) {
                        "$dayCount day${if (dayCount != 1) "s" else ""}"
                    } else {
                        "No days yet"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (program.isActive) {
                Text(
                    text = "ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotterTheme.pulse.effort,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(SpotterTheme.pulse.effortDim)
                        .padding(horizontal = SpotterTheme.spacing.sm, vertical = 3.dp),
                )
                Spacer(Modifier.width(SpotterTheme.spacing.sm))
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
