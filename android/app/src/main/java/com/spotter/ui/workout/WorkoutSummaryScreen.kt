package com.spotter.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.spotter.data.model.MuscleGroupSummary
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.formatVolume
import com.spotter.ui.theme.formatWeight

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WorkoutSummaryScreen(
    durationSeconds: Int,
    doneSets: Int,
    totalSets: Int,
    totalVolumeLb: Int,
    muscleGroups: List<MuscleGroupSummary> = emptyList(),
    navController: NavController,
) {
    val weightUnit = LocalWeightUnit.current
    Scaffold(
        topBar = { TopAppBar(title = { Text("Workout Complete") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Great work!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(32.dp))

            SummaryStat(
                label = "Duration",
                value = "%02d:%02d".format(durationSeconds / 60, durationSeconds % 60),
            )
            Spacer(Modifier.height(12.dp))
            SummaryStat(
                label = "Sets completed",
                value = "$doneSets / $totalSets",
            )
            Spacer(Modifier.height(12.dp))
            if (totalVolumeLb > 0) {
                SummaryStat(
                    label = "Total volume",
                    value = weightUnit.formatVolume(totalVolumeLb),
                )
                Spacer(Modifier.height(12.dp))
            }

            if (muscleGroups.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Muscles trained",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    muscleGroups.forEach { mg ->
                        val volumeKg = mg.volume
                        val volumeStr = if (volumeKg > 0f) {
                            " · ${weightUnit.formatWeight(volumeKg.toDouble() / 0.453592)}"
                        } else ""
                        SuggestionChip(
                            onClick = {},
                            label = { Text("${mg.muscleGroup}: ${mg.sets} sets$volumeStr") },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(40.dp))
            Button(
                onClick = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Return to Home")
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
