package com.spotter.ui.program

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.ui.components.ExercisePreviewRow
import com.spotter.ui.components.GradientButton
import com.spotter.ui.components.SpotterCard
import com.spotter.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramDetailScreen(
    programId: String,
    navController: NavController,
    viewModel: ProgramDetailViewModel = hiltViewModel(),
) {
    val programName by viewModel.programName.collectAsState()
    val days by viewModel.days.collectAsState()
    val dayExercises by viewModel.dayExercises.collectAsState()
    val availableRoutines by viewModel.availableRoutines.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(programId) { viewModel.load(programId) }

    LaunchedEffect(Unit) {
        viewModel.saved.collect { navController.popBackStack() }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(programName) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.save() }) {
                        Icon(Icons.Default.Check, contentDescription = "Save days")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            AddDayRow(
                routines = availableRoutines,
                onAdd = { routine, label -> viewModel.addDay(routine, label) },
            )

            Spacer(Modifier.height(8.dp))

            if (days.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No days yet. Add a routine above to build this program.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(days, key = { i, _ -> i }) { index, day ->
                        DayRow(
                            index = index,
                            label = day.label,
                            routineName = day.routineName,
                            exercises = day.routineId?.let { dayExercises[it] }.orEmpty(),
                            canMoveUp = index > 0,
                            canMoveDown = index < days.size - 1,
                            onMoveUp = { viewModel.moveDay(index, -1) },
                            onMoveDown = { viewModel.moveDay(index, 1) },
                            onRemove = { viewModel.removeDay(index) },
                            onEdit = day.routineId?.let { routineId ->
                                { navController.navigate(Screen.RoutineDetail.createRoute(routineId)) }
                            },
                        )
                    }
                }
            }

            GradientButton(
                text = "Save",
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddDayRow(
    routines: List<WorkoutRoutineEntity>,
    onAdd: (WorkoutRoutineEntity?, String) -> Unit,
) {
    var selectedRoutine by remember { mutableStateOf<WorkoutRoutineEntity?>(null) }
    var label by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = routines.isNotEmpty(),
            ) {
                Text(
                    selectedRoutine?.name
                        ?: if (routines.isEmpty()) "No routines available" else "Choose a routine",
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                routines.forEach { routine ->
                    DropdownMenuItem(
                        text = { Text(routine.name) },
                        onClick = { selectedRoutine = routine; expanded = false },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Day label (e.g. Push)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                onClick = {
                    onAdd(selectedRoutine, label)
                    selectedRoutine = null
                    label = ""
                },
                enabled = selectedRoutine != null || label.isNotBlank(),
            ) {
                Text("Add")
            }
        }
    }
}

@Composable
private fun DayRow(
    index: Int,
    label: String,
    routineName: String?,
    exercises: List<RoutineExerciseEntity>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    onEdit: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    SpotterCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${index + 1}. $label",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        routineName ?: "Rest / no routine",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove day",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    if (exercises.isEmpty()) {
                        Text(
                            "No exercises in this day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        exercises.forEach { ex ->
                            ExercisePreviewRow(ex)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                    if (onEdit != null) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = onEdit) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.height(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Edit workout")
                        }
                    }
                }
            }
        }
    }
}
