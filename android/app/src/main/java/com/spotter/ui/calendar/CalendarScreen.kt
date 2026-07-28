package com.spotter.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.spotter.data.model.CalendarEntry
import com.spotter.ui.components.ErrorState
import com.spotter.ui.components.ExercisePreviewRow
import design.pulse.ui.components.DataText
import com.spotter.ui.components.PulsingDots
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import com.spotter.ui.cardio.CardioFormat
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.dayChannel
import com.spotter.util.UiState
import com.spotter.util.UpcomingWorkout
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    navController: NavController,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val displayedMonth by viewModel.displayedMonth.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val projected by viewModel.projected.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val hasActiveProgram by viewModel.hasActiveProgram.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.navigateToWorkout.collect { sessionId ->
            navController.navigate(Screen.Workout.createRoute(sessionId))
        }
    }

    LaunchedEffect(actionError) {
        actionError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionError()
        }
    }

    // Re-sync the active program + schedule whenever the calendar returns to the
    // foreground (e.g. after activating a program elsewhere).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("Calendar") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Month navigation header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.prevMonth() }) {
                    Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous month")
                }
                Text(
                    text = displayedMonth.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { viewModel.nextMonth() }) {
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next month")
                }
            }

            // Day-of-week header
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { label ->
                    Text(
                        text = label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            if (!hasActiveProgram) {
                PanelCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "No active program",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Activate a program to see your workouts scheduled here. Ask the AI " +
                                "coach for a multi-day program, or pick one under Settings → Programs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PulseButton(
                                text = "Ask the coach",
                                onClick = { navController.navigate(Screen.AiChat.createRoute()) },
                            )
                            OutlinedButton(onClick = { navController.navigate(Screen.Programs.route) }) {
                                Text("Programs")
                            }
                        }
                    }
                }
            }

            when (val state = entries) {
                is UiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) { PulsingDots() }

                is UiState.Error -> ErrorState(
                    message = state.message,
                    modifier = Modifier.height(240.dp),
                )

                is UiState.Success -> {
                    val entryMap: Map<LocalDate, CalendarEntry> = state.data.associate {
                        LocalDate.parse(it.date) to it
                    }
                    // A date can carry both a strength day and a cardio run, so group rather than
                    // overwrite.
                    val projectedMap: Map<LocalDate, List<UpcomingWorkout>> = projected.groupBy { it.date }

                    MonthGrid(
                        month = displayedMonth,
                        entryMap = entryMap,
                        projectedMap = projectedMap,
                        selectedDate = selectedDate,
                        onDayClick = { date -> viewModel.selectDate(date) },
                    )

                    Spacer(Modifier.height(8.dp))

                    val selected = selectedDate
                    if (selected != null) {
                        val entry = entryMap[selected]
                        val projections = projectedMap[selected].orEmpty()
                        if (entry == null && projections.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "No workout on ${
                                        selected.format(DateTimeFormatter.ofPattern("MMMM d"))
                                    }",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (entry != null) {
                            SessionDetailCard(
                                entry = entry,
                                onResume = {
                                    navController.navigate(Screen.Workout.createRoute(entry.sessionId))
                                },
                            )
                        }
                        projections.forEach { projection ->
                            val cardio = projection.cardio
                            when {
                                cardio != null -> CardioUpcomingCard(
                                    workout = projection,
                                    onOpen = {
                                        navController.navigate(
                                            Screen.CardioOverview.createRoute(cardio.programId)
                                        )
                                    },
                                )

                                projection.routineId == null -> RestDayCard(projection)

                                else -> UpcomingDetailCard(
                                    workout = projection,
                                    onStart = {
                                        projection.routineId?.let { viewModel.startProjectedSession(it) }
                                    },
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
private fun MonthGrid(
    month: YearMonth,
    entryMap: Map<LocalDate, CalendarEntry>,
    projectedMap: Map<LocalDate, List<UpcomingWorkout>>,
    selectedDate: LocalDate?,
    onDayClick: (LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    // dayOfWeek.value: Mon=1..Sun=7. Map to Sun=0,Mon=1..Sat=6.
    val firstDay = month.atDay(1).dayOfWeek.value % 7
    val daysInMonth = month.lengthOfMonth()
    val cells: List<Int?> = List(firstDay) { null } + List(daysInMonth) { it + 1 }
    val remainder = cells.size % 7
    val paddedCells = if (remainder == 0) cells else cells + List(7 - remainder) { null }

    Column(modifier = Modifier.padding(horizontal = 4.dp)) {
        paddedCells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    if (day != null) {
                        val date = month.atDay(day)
                        val pws = projectedMap[date].orEmpty()
                        val strength = pws.firstOrNull { it.routineId != null }
                        val hasCardio = pws.any { it.cardio != null }
                        // A strength routine day wins the dot color; else a cardio run; else a
                        // strength rest day shows the quiet ring.
                        val isRestDay = strength == null && !hasCardio && pws.isNotEmpty()
                        DayCell(
                            day = day,
                            isToday = date == today,
                            isSelected = date == selectedDate,
                            entry = entryMap[date],
                            isProjected = pws.isNotEmpty(),
                            isRestDay = isRestDay,
                            isCardio = strength == null && hasCardio,
                            projectedDayIndex = strength?.dayIndex,
                            onClick = { onDayClick(date) },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    isToday: Boolean,
    isSelected: Boolean,
    entry: CalendarEntry?,
    isProjected: Boolean,
    isRestDay: Boolean,
    isCardio: Boolean,
    projectedDayIndex: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = SpotterTheme.pulse
    val circleColor = when {
        isSelected -> pulse.effort
        isToday -> pulse.effortDim
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> pulse.onEffort
        isToday -> pulse.effort
        else -> MaterialTheme.colorScheme.onSurface
    }
    // A real session always wins over a projection on the same date. Planned workouts get a
    // solid dot in their program day's channel (day 1 orange, day 2 blue, …) so the rotation
    // reads at a glance; rest days keep a quiet ring.
    val dotColor = when {
        entry?.status == "completed" -> pulse.recovery
        entry != null -> pulse.effort
        isCardio -> pulse.recovery
        isProjected && !isRestDay -> pulse.dayChannel(projectedDayIndex ?: 0)
        else -> Color.Transparent
    }
    val showRestRing = entry == null && isProjected && isRestDay

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(color = circleColor, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            DataText(
                text = "$day",
                style = SpotterTheme.dataType.numeral.copy(textAlign = TextAlign.Center),
                color = textColor,
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .then(
                    if (showRestRing) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), CircleShape)
                    } else {
                        Modifier.background(color = dotColor, shape = CircleShape)
                    },
                ),
        )
    }
}

@Composable
private fun SessionDetailCard(
    entry: CalendarEntry,
    onResume: () -> Unit,
) {
    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = LocalDate.parse(entry.date)
                        .format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.routineName ?: "Free session",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusChip(entry.status)
                    DataText(
                        text = "${entry.setCount} sets",
                        style = SpotterTheme.dataType.numeral,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.status != "completed") {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onResume) {
                    Text("Resume")
                }
            }
        }
    }
}

@Composable
private fun RestDayCard(workout: UpcomingWorkout) {
    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = workout.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = workout.dayLabel,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Recovery day — no workout scheduled.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UpcomingDetailCard(
    workout: UpcomingWorkout,
    onStart: () -> Unit,
) {
    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = workout.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
            style = MaterialTheme.typography.labelMedium,
            color = SpotterTheme.pulse.effort,
        )
        Text(
            text = workout.routineName ?: workout.dayLabel,
            style = MaterialTheme.typography.titleMedium,
        )
        if (workout.lifts.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            workout.lifts.forEach { lift ->
                ExercisePreviewRow(lift)
                Spacer(Modifier.height(2.dp))
            }
        }
        if (workout.routineId != null) {
            Spacer(Modifier.height(12.dp))
            PulseButton(
                text = "Start workout now",
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CardioUpcomingCard(
    workout: UpcomingWorkout,
    onOpen: () -> Unit,
) {
    val cardio = workout.cardio ?: return
    val pulse = SpotterTheme.pulse
    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        channel = pulse.recovery,
    ) {
        Text(
            text = workout.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
            style = MaterialTheme.typography.labelMedium,
            color = pulse.recovery,
        )
        Text(
            text = "${cardio.programName} · Week ${cardio.week} Day ${cardio.day}",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Run · ${CardioFormat.minutesLabel(cardio.totalDurationSec)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        PulseButton(
            text = "Open in Cardio",
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            channel = pulse.recovery,
            onChannel = pulse.onRecovery,
            gradient = androidx.compose.ui.graphics.SolidColor(pulse.recovery),
        )
    }
}

@Composable
private fun StatusChip(status: String) {
    val pulse = SpotterTheme.pulse
    val containerColor = when (status) {
        "completed" -> pulse.recoveryDim
        "in_progress" -> pulse.effortDim
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (status) {
        "completed" -> pulse.recovery
        "in_progress" -> pulse.effort
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (status) {
        "completed" -> "Done"
        "in_progress" -> "In Progress"
        else -> status.replaceFirstChar { it.uppercase() }
    }
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
