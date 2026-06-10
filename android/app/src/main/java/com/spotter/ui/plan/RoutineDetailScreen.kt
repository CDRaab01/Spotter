package com.spotter.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.model.RoutineExerciseOut
import com.spotter.ui.components.ErrorState
import com.spotter.ui.components.GradientButton
import com.spotter.ui.components.LoadingState
import com.spotter.ui.components.SpotterCard
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.formatWeight
import com.spotter.util.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineDetailScreen(
    routineId: String,
    navController: NavController,
    viewModel: RoutineDetailViewModel = hiltViewModel(),
) {
    val routineState by viewModel.routine.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()
    val draftExercises by viewModel.draftExercises.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val error by viewModel.error.collectAsState()
    var searchExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(routineId) {
        viewModel.loadRoutine(routineId)
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToWorkout.collect { sessionId ->
            navController.navigate(Screen.Workout.createRoute(sessionId))
        }
    }

    LaunchedEffect(searchResults) {
        searchExpanded = searchResults.isNotEmpty()
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (isEditing) {
                TopAppBar(
                    title = { Text("Edit Routine") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.cancelEdit() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.saveEdits(routineId) }) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    },
                )
            } else {
                val title = (routineState as? UiState.Success)?.data?.name ?: "Routine"
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.startEdit() }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    },
                )
            }
        },
    ) { padding ->
        when {
            isEditing -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                ) {
                    Spacer(Modifier.height(8.dp))

                    Box {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.searchQuery.value = it },
                            label = { Text("Search exercises") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        DropdownMenu(
                            expanded = searchExpanded,
                            onDismissRequest = { searchExpanded = false },
                        ) {
                            searchResults.forEach { ex ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(ex.name, style = MaterialTheme.typography.bodyMedium)
                                            ex.muscleGroup?.let {
                                                Text(
                                                    it,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    },
                                    onClick = {
                                        viewModel.addExercise(ex)
                                        viewModel.searchQuery.value = ""
                                        searchExpanded = false
                                    },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (draftExercises.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Search for exercises above to add them.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            itemsIndexed(draftExercises, key = { _, ex -> ex.exerciseId + ex.order }) { index, ex ->
                                DraftExerciseRow(
                                    draft = ex,
                                    onUpdate = { updated -> viewModel.updateExercise(index, updated) },
                                    onRemove = { viewModel.removeExercise(index) },
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                }
            }

            routineState is UiState.Loading -> LoadingState(Modifier.padding(padding))

            routineState is UiState.Error -> ErrorState(
                message = (routineState as UiState.Error).message,
                modifier = Modifier.padding(padding),
            )

            routineState is UiState.Success -> {
                val routine = (routineState as UiState.Success).data
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(routine.exercises, key = { _, ex -> ex.id }) { _, ex ->
                            ExerciseViewRow(exercise = ex)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    GradientButton(
                        text = "Start Workout",
                        onClick = { viewModel.startWorkout(routineId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun ExerciseViewRow(exercise: RoutineExerciseOut) {
    val weightUnit = LocalWeightUnit.current
    SpotterCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    exercise.exerciseName ?: exercise.exerciseId,
                    style = MaterialTheme.typography.titleSmall,
                )
                val detail = if (exercise.isBodyweight) {
                    "${exercise.targetSets}×${exercise.targetReps} BW"
                } else {
                    val weight = exercise.targetWeight?.let { "×${weightUnit.formatWeight(it)}" } ?: ""
                    "${exercise.targetSets}×${exercise.targetReps}$weight"
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                exercise.supersetGroup?.let { group ->
                    Text(
                        "Superset ${('A' + group - 1).uppercaseChar()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }
}
