package com.spotter.ui.home

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.FitnessCenter
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.ui.components.AnimatedCounter
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
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val plans by viewModel.plans.collectAsState()
    val startState by viewModel.startState.collectAsState()
    val generatingPlan by viewModel.generatingPlan.collectAsState()
    val streak by viewModel.streak.collectAsState()
    val weeklyWorkouts by viewModel.weeklyWorkouts.collectAsState()
    val upcoming by viewModel.upcoming.collectAsState()
    val greeting by viewModel.greeting.collectAsState()
    val bodyweight by viewModel.bodyweight.collectAsState()
    val isStarting = startState is UiState.Loading
    var showBodyweightDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigateToWorkout.collect { sessionId ->
            navController.navigate(Screen.Workout.createRoute(sessionId))
        }
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

    Scaffold(
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
                    IconButton(onClick = { navController.navigate(Screen.AiChat.route) }) {
                        Icon(Icons.Default.Chat, contentDescription = "AI Coach")
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
                    Icon(Icons.Default.FitnessCenter, contentDescription = "Log bodyweight")
                }
                FloatingActionButton(onClick = { navController.navigate(Screen.CreatePlan.route) }) {
                    Icon(Icons.Default.Add, contentDescription = "New plan")
                }
            }
        },
    ) { padding ->
        when (val state = plans) {
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
                    item { GreetingHeader(greeting) }
                    item { StatsBand(streak = streak, weeklyWorkouts = weeklyWorkouts, bodyweight = bodyweight) }

                    if (upcomingList.isNotEmpty()) {
                        item { SectionHeader("Upcoming workouts") }
                        items(upcomingList, key = { "${it.date}-${it.planId}-${it.dayLabel}" }) { workout ->
                            UpcomingWorkoutCard(
                                workout = workout,
                                isStarting = isStarting,
                                onStart = { workout.planId?.let { viewModel.startSession(it) } },
                            )
                        }
                    }

                    when {
                        state.data.isEmpty() && !generatingPlan -> item {
                            EmptyPlansPrompt(onChat = { navController.navigate(Screen.AiChat.route) })
                        }

                        generatingPlan && state.data.isEmpty() -> item {
                            GeneratingPlaceholder()
                        }

                        else -> {
                            item { SectionHeader("Your plans") }
                            items(state.data, key = { it.id }) { plan ->
                                PlanCard(
                                    plan = plan,
                                    isStarting = isStarting,
                                    onStart = { viewModel.startSession(plan.id) },
                                    onDelete = { viewModel.deletePlan(plan.id) },
                                    onRename = { newName -> viewModel.renamePlan(plan.id, newName) },
                                    onTapCard = { navController.navigate(Screen.PlanDetail.createRoute(plan.id)) },
                                )
                            }
                        }
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun GreetingHeader(greeting: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(SpotterTheme.brand.heroGradient)
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
private fun StatsBand(streak: Int, weeklyWorkouts: Int, bodyweight: Double?) {
    val weightUnit = LocalWeightUnit.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StreakTile(streak = streak, modifier = Modifier.weight(1f))
        StatTile(
            modifier = Modifier.weight(1f),
            animatedValue = weeklyWorkouts,
            label = "this week",
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
private fun StreakTile(streak: Int, modifier: Modifier = Modifier) {
    SpotterCard(modifier = modifier, contentPadding = 14.dp) {
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
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = workout.date.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = workout.planName ?: workout.dayLabel,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (workout.planId != null) {
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
                Icons.Default.Chat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text("No workout plans yet", style = MaterialTheme.typography.titleMedium)
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
        Text("Building your first plan…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlanCard(
    plan: WorkoutPlanEntity,
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
            currentName = plan.name,
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
            title = { Text("Delete plan?") },
            text = { Text("\"${plan.name}\" will be permanently deleted.") },
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
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onTapCard() },
            ) {
                Text(plan.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    plan.source,
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
                    Icon(Icons.Default.MoreVert, contentDescription = "Plan options")
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
        title = { Text("Rename plan") },
        text = {
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                label = { Text("Plan name") },
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
