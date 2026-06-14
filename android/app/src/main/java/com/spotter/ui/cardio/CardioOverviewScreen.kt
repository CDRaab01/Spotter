package com.spotter.ui.cardio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.ui.components.DataText
import com.spotter.ui.components.PanelCard
import com.spotter.ui.components.PulseButton
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.SpotterTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardioOverviewScreen(
    navController: NavController,
    viewModel: CardioOverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val pulse = SpotterTheme.pulse

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.programName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(SpotterTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.lg),
        ) {
            state.weeks.forEach { week ->
                item(key = "w${week.weekNumber}") {
                    Column(verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm)) {
                        // Green header bar for the week.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(pulse.recovery, MaterialTheme.shapes.small)
                                .padding(horizontal = SpotterTheme.spacing.md, vertical = SpotterTheme.spacing.sm),
                        ) {
                            Text(
                                "Week ${week.weekNumber}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = pulse.onRecovery,
                            )
                        }
                        week.days.forEach { day ->
                            CardioDayRow(
                                day = day,
                                intro = week.intro,
                                onResume = {
                                    viewModel.resume(day)
                                    navController.navigate(Screen.CardioRun.route)
                                },
                                onStart = {
                                    viewModel.start(day)
                                    navController.navigate(Screen.CardioRun.route)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardioDayRow(
    day: CardioDayUi,
    intro: String,
    onResume: () -> Unit,
    onStart: () -> Unit,
) {
    val pulse = SpotterTheme.pulse
    val dim = day.status == CardioDayStatus.UPCOMING
    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dim) 0.6f else 1f),
        channel = when (day.status) {
            CardioDayStatus.CURRENT -> pulse.recovery
            else -> null
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DayMarker(day.status)
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = SpotterTheme.spacing.md),
            ) {
                Text("Day ${day.day}", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle(day),
                    style = MaterialTheme.typography.bodySmall,
                    color = when (day.status) {
                        CardioDayStatus.DONE -> pulse.streak
                        CardioDayStatus.CURRENT -> pulse.recovery
                        CardioDayStatus.UPCOMING -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            DataText(
                text = CardioFormat.minutesLabel(day.totalDurationSec),
                style = SpotterTheme.dataType.numeral,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (day.status == CardioDayStatus.CURRENT) {
            Text(
                text = intro,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = SpotterTheme.spacing.sm),
            )
            IntervalBar(
                intervals = day.intervals,
                showLabels = true,
                modifier = Modifier.padding(top = SpotterTheme.spacing.md),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SpotterTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
            ) {
                if (day.attemptedToday) {
                    PulseButton(
                        text = "Resume",
                        onClick = onResume,
                        compact = true,
                        channel = pulse.recovery,
                        onChannel = pulse.onRecovery,
                        gradient = SolidColor(pulse.recovery),
                    )
                    OutlinedButton(
                        onClick = onStart,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = pulse.recovery),
                        border = androidx.compose.foundation.BorderStroke(1.dp, pulse.recovery),
                    ) { Text("Restart") }
                } else {
                    PulseButton(
                        text = "Start",
                        onClick = onStart,
                        compact = true,
                        channel = pulse.recovery,
                        onChannel = pulse.onRecovery,
                        gradient = SolidColor(pulse.recovery),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayMarker(status: CardioDayStatus) {
    val pulse = SpotterTheme.pulse
    val (icon, tint) = when (status) {
        CardioDayStatus.DONE -> Icons.Filled.EmojiEvents to pulse.streak
        CardioDayStatus.CURRENT -> Icons.Filled.Star to pulse.recovery
        CardioDayStatus.UPCOMING -> Icons.Filled.Star to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .size(36.dp)
            .background(tint.copy(alpha = 0.15f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

private fun subtitle(day: CardioDayUi): String = when (day.status) {
    CardioDayStatus.DONE ->
        day.completedDate?.let { "Completed ${CardioFormat.longDate(it)}" } ?: "Completed"
    CardioDayStatus.CURRENT ->
        if (day.attemptedToday) "Attempted today"
        else day.targetDate?.let { "Target: ${CardioFormat.shortDate(it)}" } ?: "Up next"
    CardioDayStatus.UPCOMING ->
        day.targetDate?.let { "Target: ${CardioFormat.shortDate(it)}" } ?: "Upcoming"
}
