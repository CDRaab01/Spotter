package com.spotter.ui.cardio

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.model.CardioProgram
import com.spotter.data.model.CardioProgramType
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalDistanceUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.formatDistance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardioHomeScreen(
    navController: NavController,
    viewModel: CardioHomeViewModel = hiltViewModel(),
) {
    val recentSessions by viewModel.recentSessions.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Cardio") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(SpotterTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.md),
        ) {
            items(CardioPrograms.all, key = { it.id }) { program ->
                CardioProgramCard(
                    program = program,
                    onClick = {
                        when (program.type) {
                            CardioProgramType.GUIDED ->
                                navController.navigate(Screen.CardioOverview.createRoute(program.id))
                            CardioProgramType.FREE ->
                                navController.navigate(Screen.FreeRunConfig.route)
                        }
                    },
                )
            }
            item(key = "log_cardio") {
                LogCardioCard(onClick = { navController.navigate(Screen.ManualCardio.route) })
            }
            if (recentSessions.isNotEmpty()) {
                item(key = "recent_header") {
                    SectionHeader(label = "Recent activity", channel = SpotterTheme.pulse.recovery)
                }
                item(key = "recent_list") {
                    PanelCard(modifier = Modifier.fillMaxWidth()) {
                        recentSessions.forEachIndexed { index, session ->
                            RecentCardioRow(session)
                            if (index < recentSessions.lastIndex) {
                                Spacer(Modifier.height(SpotterTheme.spacing.sm))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCardioRow(session: CardioSessionEntity) {
    val distanceUnit = LocalDistanceUnit.current
    val date = CardioFormat.parseDate(session.completedAt ?: session.startedAt)
    val detail = buildList {
        date?.let { add(CardioFormat.shortDate(it)) }
        add(CardioFormat.minutesLabel(session.totalElapsedSec))
        session.distanceMeters?.let { add(distanceUnit.formatDistance(it)) }
    }.joinToString(" · ")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(recentSessionLabel(session), style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.DirectionsRun,
            contentDescription = null,
            tint = SpotterTheme.pulse.recovery,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** "C25K · W2 D1", "Run", "Walk", or the program's name. */
private fun recentSessionLabel(session: CardioSessionEntity): String {
    if (session.programId == "manual") {
        return when (session.activityType) {
            "run" -> "Run"
            "walk" -> "Walk"
            else -> "Cardio"
        }
    }
    val name = CardioPrograms.byId(session.programId)?.name ?: "Cardio"
    val weekDay = if (session.weekNumber != null && session.dayNumber != null) {
        " · W${session.weekNumber} D${session.dayNumber}"
    } else {
        ""
    }
    return name + weekDay
}

@Composable
private fun LogCardioCard(onClick: () -> Unit) {
    val pulse = SpotterTheme.pulse
    val channel = pulse.recovery
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        channel = channel,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = channel,
                modifier = Modifier.size(28.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = SpotterTheme.spacing.md),
            ) {
                Text("Log cardio", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add a walk or run you already did",
                    style = MaterialTheme.typography.labelMedium,
                    color = channel,
                )
            }
        }
    }
}

@Composable
private fun CardioProgramCard(
    program: CardioProgram,
    onClick: () -> Unit,
) {
    val pulse = SpotterTheme.pulse
    val channel = pulse.recovery
    val icon: ImageVector = when (program.type) {
        CardioProgramType.GUIDED -> Icons.Filled.DirectionsRun
        CardioProgramType.FREE -> Icons.Filled.Bolt
    }
    val subtitle = when (program.type) {
        CardioProgramType.GUIDED -> "${program.weeks?.size ?: 0}-week guided plan · 3 days a week"
        CardioProgramType.FREE -> "Open-ended or custom intervals"
    }
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        channel = channel,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = channel,
                modifier = Modifier.size(28.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = SpotterTheme.spacing.md),
            ) {
                Text(program.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = channel,
                )
            }
        }
        Text(
            program.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = SpotterTheme.spacing.sm),
        )
    }
}
