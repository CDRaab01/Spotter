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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.ui.components.GradientButton
import com.spotter.ui.components.SpotterCard
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.formatWeightLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePlanScreen(
    navController: NavController,
    viewModel: CreatePlanViewModel = hiltViewModel(),
) {
    val planName by viewModel.planName.collectAsState()
    val exercises by viewModel.exercises.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val saveError by viewModel.saveError.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigateBack.collect { navController.popBackStack() }
    }

    LaunchedEffect(saveError) {
        saveError?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Long)
            viewModel.clearError()
        }
    }

    LaunchedEffect(searchResults) {
        searchExpanded = searchResults.isNotEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New Plan") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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

            OutlinedTextField(
                value = planName,
                onValueChange = { viewModel.planName.value = it },
                label = { Text("Plan name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))

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

            if (exercises.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Search for exercises above to add them to your plan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(exercises, key = { _, ex -> ex.exerciseId + ex.order }) { index, ex ->
                        DraftExerciseRow(
                            draft = ex,
                            onUpdate = { updated -> viewModel.updateExercise(index, updated) },
                            onRemove = { viewModel.removeExercise(index) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            GradientButton(
                text = "Save Plan",
                onClick = { viewModel.savePlan() },
                modifier = Modifier.fillMaxWidth(),
                enabled = planName.isNotBlank() && exercises.isNotEmpty(),
            )

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
internal fun DraftExerciseRow(
    draft: DraftExercise,
    onUpdate: (DraftExercise) -> Unit,
    onRemove: () -> Unit,
) {
    var setsText by remember(draft.exerciseId) { mutableStateOf(draft.targetSets.toString()) }
    var repsText by remember(draft.exerciseId) { mutableStateOf(draft.targetReps.toString()) }
    var weightText by remember(draft.exerciseId) {
        mutableStateOf(draft.targetWeight?.toString() ?: "")
    }

    SpotterCard(modifier = Modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    draft.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = setsText,
                    onValueChange = { v ->
                        setsText = v
                        v.toIntOrNull()?.let { onUpdate(draft.copy(targetSets = it)) }
                    },
                    label = { Text("Sets") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { v ->
                        repsText = v
                        v.toIntOrNull()?.let { onUpdate(draft.copy(targetReps = it)) }
                    },
                    label = { Text("Reps") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                if (!draft.isBodyweight) {
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { v ->
                            weightText = v.filter { c -> c.isDigit() || c == '.' }
                            onUpdate(draft.copy(targetWeight = weightText.toDoubleOrNull()))
                        },
                        label = { Text(LocalWeightUnit.current.formatWeightLabel()) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                } else {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = "BW",
                            onValueChange = {},
                            label = { Text("Weight") },
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
                SupersetSelector(
                    group = draft.supersetGroup,
                    onChange = { onUpdate(draft.copy(supersetGroup = it)) },
                    modifier = Modifier.weight(0.9f),
                )
            }
        }
    }
}

/** Renders a superset group number as its workout-screen letter (1 -> "A"). */
internal fun supersetLabel(group: Int?): String =
    if (group == null || group < 1) "" else ('A' + group - 1).toString()

/**
 * Letter-based picker for the superset group, mapping None/A/B/C/D to the
 * nullable Int the API expects. Matches the "Superset A" rendering used in
 * workout mode ([com.spotter.ui.workout.WorkoutScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SupersetSelector(
    group: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf<Int?>(null, 1, 2, 3, 4)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = if (group == null) "None" else supersetLabel(group),
            onValueChange = {},
            readOnly = true,
            label = { Text("Superset") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(if (opt == null) "None" else "Group ${supersetLabel(opt)}") },
                    onClick = { onChange(opt); expanded = false },
                )
            }
        }
    }
}
