package com.spotter.ui.program

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
import androidx.compose.material.icons.filled.Delete
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
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.ui.components.GradientButton
import com.spotter.ui.components.SpotterCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramDetailScreen(
    programId: String,
    navController: NavController,
    viewModel: ProgramDetailViewModel = hiltViewModel(),
) {
    val programName by viewModel.programName.collectAsState()
    val days by viewModel.days.collectAsState()
    val availablePlans by viewModel.availablePlans.collectAsState()
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
                plans = availablePlans,
                onAdd = { plan, label -> viewModel.addDay(plan, label) },
            )

            Spacer(Modifier.height(8.dp))

            if (days.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No days yet. Add a plan above to build this program.",
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
                            planName = day.planName,
                            canMoveUp = index > 0,
                            canMoveDown = index < days.size - 1,
                            onMoveUp = { viewModel.moveDay(index, -1) },
                            onMoveDown = { viewModel.moveDay(index, 1) },
                            onRemove = { viewModel.removeDay(index) },
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
    plans: List<WorkoutPlanEntity>,
    onAdd: (WorkoutPlanEntity?, String) -> Unit,
) {
    var selectedPlan by remember { mutableStateOf<WorkoutPlanEntity?>(null) }
    var label by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = plans.isNotEmpty(),
            ) {
                Text(
                    selectedPlan?.name
                        ?: if (plans.isEmpty()) "No plans available" else "Choose a plan",
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                plans.forEach { plan ->
                    DropdownMenuItem(
                        text = { Text(plan.name) },
                        onClick = { selectedPlan = plan; expanded = false },
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
                    onAdd(selectedPlan, label)
                    selectedPlan = null
                    label = ""
                },
                enabled = selectedPlan != null || label.isNotBlank(),
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
    planName: String?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    SpotterCard(modifier = Modifier.fillMaxWidth(), contentPadding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${index + 1}. $label",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    planName ?: "Rest / no plan",
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
    }
}
