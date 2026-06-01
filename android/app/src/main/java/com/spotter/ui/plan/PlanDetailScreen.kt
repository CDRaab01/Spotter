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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.spotter.data.model.PlannedExerciseOut
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.formatWeight
import com.spotter.util.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanDetailScreen(
    planId: String,
    navController: NavController,
    viewModel: PlanDetailViewModel = hiltViewModel(),
) {
    val planState by viewModel.plan.collectAsState()
    val isEditing by viewModel.isEditing.collectAsState()
    val draftExercises by viewModel.draftExercises.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    var searchExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(planId) {
        viewModel.loadPlan(planId)
    }

    LaunchedEffect(Unit) {
        viewModel.navigateToWorkout.collect { sessionId ->
            navController.navigate(Screen.Workout.createRoute(sessionId))
        }
    }

    LaunchedEffect(searchResults) {
        searchExpanded = searchResults.isNotEmpty()
    }

    Scaffold(
        topBar = {
            if (isEditing) {
                TopAppBar(
                    title = { Text("Edit Plan") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.cancelEdit() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.saveEdits(planId) }) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    },
                )
            } else {
                val title = (planState as? UiState.Success)?.data?.name ?: "Plan"
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

            planState is UiState.Loading -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            planState is UiState.Error -> {
                Box(
                    Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        (planState as UiState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            planState is UiState.Success -> {
                val plan = (planState as UiState.Success).data
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
                        itemsIndexed(plan.exercises, key = { _, ex -> ex.id }) { _, ex ->
                            ExerciseViewRow(exercise = ex)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.startWorkout(planId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    ) {
                        Text("Start Workout")
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun ExerciseViewRow(exercise: PlannedExerciseOut) {
    val weightUnit = LocalWeightUnit.current
    Card(modifier = Modifier.fillMaxWidth()) {
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
