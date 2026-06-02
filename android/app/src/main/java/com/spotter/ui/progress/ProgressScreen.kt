package com.spotter.ui.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.model.ExerciseProgressPoint
import com.spotter.data.model.TrackedExercise
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.formatWeight
import com.spotter.ui.theme.formatWeightFieldLabel
import com.spotter.ui.theme.formatWeightNullable
import com.spotter.util.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    navController: NavController,
    viewModel: ProgressViewModel = hiltViewModel(),
) {
    val metrics by viewModel.metrics.collectAsState()
    val trackedExercises by viewModel.trackedExercises.collectAsState()
    val exerciseProgress by viewModel.exerciseProgress.collectAsState()
    val selectedExerciseId by viewModel.selectedExerciseId.collectAsState()
    val chartRange by viewModel.chartRange.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showBodyweightDialog by remember { mutableStateOf(false) }

    if (showBodyweightDialog) {
        BodyweightLogDialog(
            onDismiss = { showBodyweightDialog = false },
            onConfirm = { weight ->
                viewModel.logBodyweight(weight)
                showBodyweightDialog = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Progress") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = { showBodyweightDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Log bodyweight")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Body Weight") },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Strength") },
                )
            }

            when (selectedTab) {
                0 -> BodyWeightTab(
                    metrics = metrics,
                    chartRange = chartRange,
                    onRangeSelect = { viewModel.setChartRange(it) },
                )
                1 -> StrengthTab(
                    trackedExercises = trackedExercises,
                    exerciseProgress = exerciseProgress,
                    selectedExerciseId = selectedExerciseId,
                    onSelectExercise = { id -> viewModel.selectExercise(id) },
                    chartRange = chartRange,
                    onRangeSelect = { viewModel.setChartRange(it) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(
    selected: ChartRange,
    onSelect: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChartRange.entries.forEach { range ->
            FilterChip(
                selected = selected == range,
                onClick = { onSelect(range) },
                label = { Text(range.label) },
            )
        }
    }
}

@Composable
private fun BodyWeightTab(
    metrics: UiState<List<BodyMetricEntity>>,
    chartRange: ChartRange,
    onRangeSelect: (ChartRange) -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    when (metrics) {
        is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(metrics.message, color = MaterialTheme.colorScheme.error)
        }
        is UiState.Success -> {
            if (metrics.data.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No weight entries yet. Use the + button to log.")
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    RangeSelector(selected = chartRange, onSelect = onRangeSelect)
                    val points = metrics.data.map { it.weight.toFloat() }
                    val chartColor = MaterialTheme.colorScheme.primary
                    LineChart(
                        points = points,
                        color = chartColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(16.dp),
                    )
                    LazyColumn {
                        items(metrics.data.reversed()) { metric ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(metric.date, style = MaterialTheme.typography.bodyMedium)
                                val bodyfatText = metric.bodyfat?.let { " · ${it}% bf" } ?: ""
                                Text(
                                    "${weightUnit.formatWeight(metric.weight)}$bodyfatText",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun StrengthTab(
    trackedExercises: UiState<List<TrackedExercise>>,
    exerciseProgress: UiState<List<ExerciseProgressPoint>>,
    selectedExerciseId: String?,
    onSelectExercise: (String?) -> Unit,
    chartRange: ChartRange,
    onRangeSelect: (ChartRange) -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    Column(modifier = Modifier.fillMaxSize()) {
        when (trackedExercises) {
            is UiState.Loading -> Box(
                Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is UiState.Success -> {
                if (trackedExercises.data.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        Text("Complete workouts to see strength progress.")
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        trackedExercises.data.forEach { exercise ->
                            val isSelected = exercise.exerciseId == selectedExerciseId
                            Surface(
                                modifier = Modifier.clickable {
                                    onSelectExercise(
                                        if (isSelected) null else exercise.exerciseId
                                    )
                                },
                                shape = MaterialTheme.shapes.small,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    exercise.exerciseName,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            else -> Unit
        }

        when (exerciseProgress) {
            is UiState.Loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            is UiState.Success -> {
                val data = exerciseProgress.data
                if (data.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No data for this range — try a wider window.")
                    }
                } else {
                    Column {
                        RangeSelector(selected = chartRange, onSelect = onRangeSelect)
                        val points = data.mapNotNull { it.maxWeight?.toFloat() }
                        if (points.size >= 2) {
                            val chartColor = MaterialTheme.colorScheme.primary
                            LineChart(
                                points = points,
                                color = chartColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .padding(16.dp),
                            )
                        }
                        LazyColumn {
                            items(data.reversed()) { point ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(point.date, style = MaterialTheme.typography.bodyMedium)
                                    val wt = weightUnit.formatWeightNullable(point.maxWeight)
                                    Text(
                                        "${point.maxReps} reps · $wt",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            is UiState.Idle -> {
                if (selectedExerciseId == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Select an exercise above to view progress.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun LineChart(
    points: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) return
    Canvas(modifier = modifier.background(MaterialTheme.colorScheme.surface)) {
        val minVal = points.min()
        val maxVal = points.max()
        val range = (maxVal - minVal).coerceAtLeast(1f)
        val padX = 16.dp.toPx()
        val padY = 12.dp.toPx()
        val chartW = size.width - padX * 2
        val chartH = size.height - padY * 2
        val stepX = chartW / (points.size - 1)

        fun xOf(i: Int) = padX + i * stepX
        fun yOf(v: Float) = padY + chartH - ((v - minVal) / range) * chartH

        val path = Path()
        points.forEachIndexed { i, v ->
            if (i == 0) path.moveTo(xOf(i), yOf(v)) else path.lineTo(xOf(i), yOf(v))
        }
        drawPath(path, color = color, style = Stroke(width = 2.dp.toPx()))

        points.forEachIndexed { i, v ->
            drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(xOf(i), yOf(v)))
        }
    }
}

@Composable
private fun BodyweightLogDialog(
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    var weightText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log bodyweight") },
        text = {
            OutlinedTextField(
                value = weightText,
                onValueChange = { weightText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(weightUnit.formatWeightFieldLabel()) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { weightText.toDoubleOrNull()?.let { onConfirm(it) } },
                enabled = weightText.toDoubleOrNull() != null,
            ) { Text("Log") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
