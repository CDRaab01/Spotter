package com.spotter.ui.cardio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.spotter.data.model.CardioProgram
import com.spotter.data.model.CardioProgramType
import design.pulse.ui.components.PanelCard
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.SpotterTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardioHomeScreen(navController: NavController) {
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
