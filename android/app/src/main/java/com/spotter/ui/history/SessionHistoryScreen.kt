package com.spotter.ui.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.model.SessionSummary
import com.spotter.ui.components.EmptyState
import com.spotter.ui.components.ErrorState
import com.spotter.ui.components.LoadingState
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.navigation.Screen
import com.spotter.util.UiState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(
    navController: NavController,
    viewModel: SessionHistoryViewModel = hiltViewModel(),
) {
    val sessionsState by viewModel.sessions.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workout History") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val state = sessionsState) {
            is UiState.Loading -> LoadingState(Modifier.padding(padding))

            is UiState.Error -> ErrorState(
                message = state.message,
                modifier = Modifier.padding(padding),
            )

            is UiState.Success -> {
                if (state.data.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.History,
                        title = "No history yet",
                        subtitle = "Your completed workouts will show up here.",
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.data, key = { it.id }) { session ->
                            SessionCard(
                                session = session,
                                onTap = {
                                    if (session.status == "in_progress") {
                                        navController.navigate(Screen.Workout.createRoute(session.id))
                                    }
                                },
                                onDelete = { viewModel.deleteSession(session.id) },
                            )
                        }
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionSummary,
    onTap: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete workout?") },
            text = { Text("This permanently removes this session and its logged sets.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    PanelCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        formatDate(session.date),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        session.routineName ?: "No Routine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusBadge(session.status)
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    session.durationSeconds?.let { secs ->
                        DataText(
                            text = formatDuration(secs),
                            style = SpotterTheme.dataType.numeral,
                        )
                    }
                    DataText(
                        text = "${session.completedSets}/${session.totalSets} sets",
                        style = SpotterTheme.dataType.numeral,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Divider()
                    session.exercises.forEach { ex ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(ex.exerciseName, style = MaterialTheme.typography.bodySmall)
                            DataText(
                                text = "${ex.completedSets}/${ex.totalSets}",
                                style = SpotterTheme.dataType.numeral,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete session",
                                modifier = Modifier.width(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: String) {
    val pulse = SpotterTheme.pulse
    val (label, color) = when (status) {
        "completed" -> "Done" to pulse.recovery
        "in_progress" -> "Active" to pulse.effort
        else -> status to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

private fun formatDate(dateStr: String): String {
    return try {
        val date = LocalDate.parse(dateStr)
        date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
    } catch (e: Exception) {
        dateStr
    }
}

private fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    return if (minutes >= 60) {
        val h = minutes / 60
        val m = minutes % 60
        if (m == 0) "${h}h" else "${h}h ${m}m"
    } else {
        "${minutes}m"
    }
}
