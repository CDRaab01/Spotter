package com.spotter.ui.recap

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.model.WeeklyRecapOut
import com.spotter.ui.components.EmptyState
import com.spotter.ui.components.ErrorState
import com.spotter.ui.components.LoadingState
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.formatVolume
import com.spotter.ui.theme.formatWeight
import com.spotter.util.UiState
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import design.pulse.ui.components.StatTile
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Your week" — the weekly recap. The numbers are always server-computed and always present;
 * the narrative is the LLM's take and is simply absent when LM Studio was unreachable (a normal
 * state, not an error). Only a failure to reach the *server* produces the error/retry state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyRecapScreen(
    navController: NavController,
    viewModel: WeeklyRecapViewModel = hiltViewModel(),
) {
    val state by viewModel.recap.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Your week") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val recap = state) {
            is UiState.Loading -> LoadingState(Modifier.padding(padding))

            is UiState.Error -> ErrorState(
                message = recap.message,
                modifier = Modifier.padding(padding),
                onRetry = { viewModel.load() },
            )

            is UiState.Success -> WeeklyRecapContent(
                recap = recap.data,
                modifier = Modifier.padding(padding),
            )

            else -> Unit
        }
    }
}

@Composable
private fun WeeklyRecapContent(recap: WeeklyRecapOut, modifier: Modifier = Modifier) {
    val pulse = SpotterTheme.pulse
    val spacing = SpotterTheme.spacing
    val weightUnit = LocalWeightUnit.current
    val stats = recap.stats
    val volumeLb = stats.totalVolumeLb.roundToInt()
    val nothingLogged = stats.strengthSessions == 0 && stats.cardioSessions == 0 &&
        stats.activeMinutes == 0 && stats.prs == 0 && volumeLb == 0

    if (nothingLogged) {
        EmptyState(
            icon = Icons.Default.CalendarMonth,
            title = "Nothing logged this week yet",
            modifier = modifier,
            subtitle = "Finish one session and your recap fills in — sessions, volume, PRs, " +
                "and your coach's read on the week.",
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Text(
                text = weekLabel(recap.weekStart),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The centerpiece, mirroring the post-workout summary: total volume in effort blue.
        if (volumeLb > 0) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    DataText(
                        text = weightUnit.formatVolume(volumeLb),
                        style = SpotterTheme.dataType.dataLarge,
                        color = pulse.effort,
                    )
                    Text(
                        text = "TOTAL VOLUME",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    dense = true,
                    animatedValue = stats.strengthSessions,
                    label = "sessions",
                    channel = pulse.effort,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    dense = true,
                    animatedValue = stats.cardioSessions,
                    label = "runs",
                    channel = pulse.recovery,
                )
                StatTile(
                    modifier = Modifier.weight(1f),
                    dense = true,
                    animatedValue = stats.activeMinutes,
                    label = "active min",
                    channel = pulse.effort,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                StatTile(
                    modifier = Modifier.weight(1f),
                    dense = true,
                    animatedValue = stats.prs,
                    label = if (stats.prs == 1) "personal record" else "personal records",
                    channel = if (stats.prs > 0) pulse.strength else null,
                )
                // Bodyweight movement is reported, not judged: the direction is carried by the
                // arrow + channel (up violet like loads, down blue), never by "good"/"bad" color.
                stats.bodyweightDeltaLb?.let { delta ->
                    val up = delta >= 0
                    StatTile(
                        modifier = Modifier.weight(1f),
                        dense = true,
                        value = (if (up) "+" else "−") + weightUnit.formatWeight(abs(delta)),
                        label = "bodyweight",
                        channel = if (up) pulse.strength else pulse.effort,
                        icon = {
                            Icon(
                                imageVector = if (up) Icons.AutoMirrored.Filled.TrendingUp
                                              else Icons.AutoMirrored.Filled.TrendingDown,
                                contentDescription = null,
                                tint = if (up) pulse.strength else pulse.effort,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                } ?: Spacer(Modifier.weight(1f))
            }
        }

        item { SectionHeader(label = "Your week", channel = pulse.strength) }
        item {
            PanelCard(modifier = Modifier.fillMaxWidth(), channel = pulse.strength) {
                val narrative = recap.narrative?.takeIf { it.isNotBlank() }
                if (narrative != null) {
                    Text(
                        text = narrative,
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                    )
                } else {
                    // The LLM being down is expected here — the numbers above are the product.
                    Text(
                        text = "Coach narration unavailable — here are your numbers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(spacing.lg)) }
    }
}

/** "WEEK OF JUL 21" from an ISO week-start date; falls back to the raw string if unparseable. */
private fun weekLabel(weekStart: String): String {
    val date = runCatching { LocalDate.parse(weekStart) }.getOrNull() ?: return weekStart
    return "WEEK OF ${date.format(DateTimeFormatter.ofPattern("MMM d")).uppercase()}"
}
