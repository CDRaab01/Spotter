package com.spotter.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.ui.components.ErrorState
import com.spotter.ui.components.LoadingState
import com.spotter.ui.theme.SpotterTheme
import com.spotter.util.UiState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.SectionHeader

/**
 * Exercise detail — a functional first version: name, primary + secondary muscles, equipment,
 * and instructions text, loaded mirror-backed so it works offline. Reached by tapping the
 * exercise name in workout mode. (Charts/media enrichment lands in a later round.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(
    exerciseId: String,
    navController: NavController,
    viewModel: ExerciseDetailViewModel = hiltViewModel(),
) {
    val exercise by viewModel.exercise.collectAsState()

    LaunchedEffect(exerciseId) { viewModel.load(exerciseId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Exercise") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when (val state = exercise) {
            is UiState.Loading -> LoadingState(Modifier.padding(padding))

            is UiState.Error -> ErrorState(
                message = state.message,
                modifier = Modifier.padding(padding),
                onRetry = { viewModel.load(exerciseId) },
            )

            is UiState.Success -> {
                val ex = state.data
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(SpotterTheme.spacing.lg)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.md),
                ) {
                    PanelCard(modifier = Modifier.fillMaxWidth()) {
                        Text(ex.name, style = MaterialTheme.typography.headlineSmall)
                        val subtitle = listOfNotNull(ex.muscleGroup, ex.equipment)
                            .joinToString(" · ")
                        if (subtitle.isNotEmpty()) {
                            Spacer(Modifier.height(SpotterTheme.spacing.xs))
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    val secondary = ex.secondaryMuscles
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    if (ex.muscleGroup != null || secondary.isNotEmpty()) {
                        PanelCard(modifier = Modifier.fillMaxWidth()) {
                            SectionHeader("Muscles")
                            Spacer(Modifier.height(SpotterTheme.spacing.sm))
                            ex.muscleGroup?.let { primary ->
                                LabelValueRow("Primary", primary)
                            }
                            if (secondary.isNotEmpty()) {
                                Spacer(Modifier.height(SpotterTheme.spacing.xs))
                                LabelValueRow("Secondary", secondary.joinToString(", "))
                            }
                        }
                    }

                    val instructions = ex.instructions?.takeIf { it.isNotBlank() }
                    PanelCard(modifier = Modifier.fillMaxWidth()) {
                        SectionHeader("How to")
                        Spacer(Modifier.height(SpotterTheme.spacing.sm))
                        Text(
                            instructions ?: "No instructions for this exercise yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (instructions != null) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun LabelValueRow(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
