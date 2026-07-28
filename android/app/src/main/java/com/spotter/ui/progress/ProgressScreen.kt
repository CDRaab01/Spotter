package com.spotter.ui.progress

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.local.entity.BodyMetricEntity
import com.spotter.data.model.ExerciseProgressPoint
import com.spotter.data.model.PersonalRecord
import com.spotter.data.model.TrackedExercise
import design.pulse.ui.components.DataText
import com.spotter.ui.components.EmptyState
import com.spotter.ui.components.ErrorState
import com.spotter.ui.components.LoadingState
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import com.spotter.ui.navigation.Screen
import design.pulse.ui.theme.PulseMotion
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.formatWeight
import com.spotter.ui.theme.formatWeightFieldLabel
import com.spotter.ui.theme.formatWeightNullable
import com.spotter.ui.theme.measurementLabel
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
    val personalRecords by viewModel.personalRecords.collectAsState()
    val actionError by viewModel.actionError.collectAsState()
    val pulse = SpotterTheme.pulse

    var selectedTab by remember { mutableIntStateOf(0) }
    var showBodyweightDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(actionError) {
        actionError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearActionError()
        }
    }

    if (showBodyweightDialog) {
        BodyweightLogDialog(
            onDismiss = { showBodyweightDialog = false },
            onConfirm = { draft ->
                viewModel.logBodyweight(draft)
                showBodyweightDialog = false
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Progress") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.SessionHistory.route) }) {
                        Icon(Icons.Default.History, contentDescription = "Workout history")
                    }
                },
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = { showBodyweightDialog = true },
                    containerColor = pulse.effort,
                    contentColor = pulse.onEffort,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Log bodyweight")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The tabs carry their channel: bodyweight + records read in their own colors.
            val tabChannel = when (selectedTab) {
                1 -> pulse.strength
                2 -> pulse.strength
                else -> pulse.effort
            }
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onSurface,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        height = 2.dp,
                        color = tabChannel,
                    )
                },
                divider = {},
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Body Weight") },
                    selectedContentColor = pulse.effort,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Strength") },
                    selectedContentColor = pulse.strength,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Records") },
                    selectedContentColor = pulse.strength,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    onRetry = { viewModel.retryTrackedExercises() },
                )
                2 -> RecordsTab(records = personalRecords)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(
    selected: ChartRange,
    onSelect: (ChartRange) -> Unit,
    channel: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SpotterTheme.spacing.lg, vertical = SpotterTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
    ) {
        ChartRange.entries.forEach { range ->
            FilterChip(
                selected = selected == range,
                onClick = { onSelect(range) },
                label = { Text(range.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = channel.copy(alpha = 0.16f),
                    selectedLabelColor = channel,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == range,
                    borderColor = SpotterTheme.pulse.hairline,
                    selectedBorderColor = channel.copy(alpha = 0.4f),
                    selectedBorderWidth = 1.dp,
                ),
            )
        }
    }
}

/** `internal` so the exercise-detail screen renders the genuine toggle instead of a copy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StrengthMetricToggle(
    selected: StrengthMetric,
    onSelect: (StrengthMetric) -> Unit,
) {
    val channel = SpotterTheme.pulse.strength
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpotterTheme.spacing.lg, vertical = SpotterTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
    ) {
        StrengthMetric.entries.forEach { metric ->
            FilterChip(
                selected = selected == metric,
                onClick = { onSelect(metric) },
                label = { Text(metric.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = channel.copy(alpha = 0.16f),
                    selectedLabelColor = channel,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = selected == metric,
                    borderColor = SpotterTheme.pulse.hairline,
                    selectedBorderColor = channel.copy(alpha = 0.4f),
                    selectedBorderWidth = 1.dp,
                ),
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
    val pulse = SpotterTheme.pulse
    when (metrics) {
        is UiState.Loading -> LoadingState()
        is UiState.Error -> ErrorState(message = metrics.message)
        is UiState.Success -> {
            if (metrics.data.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.MonitorWeight,
                    title = "No weigh-ins yet",
                    subtitle = "Tap + to log your bodyweight and watch the trend build.",
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    RangeSelector(selected = chartRange, onSelect = onRangeSelect, channel = pulse.effort)
                    val points = metrics.data.map { it.weight.toFloat() }
                    ChartCard {
                        LineChart(
                            points = points,
                            color = pulse.effort,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                        )
                    }
                    val trends = measurementTrends(metrics.data)
                    if (trends.isNotEmpty()) {
                        MeasurementsCard(trends)
                    }
                    LazyColumn {
                        items(metrics.data.reversed()) { metric ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = SpotterTheme.spacing.lg,
                                        vertical = SpotterTheme.spacing.sm,
                                    ),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(metric.date, style = MaterialTheme.typography.bodyMedium)
                                val bodyfatText = metric.bodyfat?.let { " · ${it}% bf" } ?: ""
                                DataText(
                                    text = "${weightUnit.formatWeight(metric.weight)}$bodyfatText",
                                    style = SpotterTheme.dataType.numeral,
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

/** The latest value of a tape measurement, with its change since the prior time it was recorded. */
private data class MeasurementTrend(val label: String, val value: Double, val delta: Double?)

/** For each measurement, the most recent value + its delta vs the previous entry that had it. */
private fun measurementTrends(ascending: List<BodyMetricEntity>): List<MeasurementTrend> {
    val selectors: List<Pair<String, (BodyMetricEntity) -> Double?>> = listOf(
        "Neck" to { it.neck },
        "Chest" to { it.chest },
        "Waist" to { it.waist },
        "Hips" to { it.hips },
        "Arm" to { it.arm },
        "Thigh" to { it.thigh },
    )
    return selectors.mapNotNull { (label, selector) ->
        val values = ascending.mapNotNull(selector)
        val latest = values.lastOrNull() ?: return@mapNotNull null
        val previous = values.getOrNull(values.size - 2)
        MeasurementTrend(label, latest, previous?.let { latest - it })
    }
}

private fun formatMeasurement(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)

/** A compact tape-measurement panel: the latest girths in a two-column grid, each with its trend. */
@Composable
private fun MeasurementsCard(trends: List<MeasurementTrend>) {
    val unit = LocalWeightUnit.current.measurementLabel()
    val pulse = SpotterTheme.pulse
    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpotterTheme.spacing.lg, vertical = SpotterTheme.spacing.sm),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm)) {
            Text(
                "Measurements",
                style = MaterialTheme.typography.titleSmall,
                color = pulse.effort,
            )
            trends.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.md)) {
                    pair.forEach { trend ->
                        MeasurementCell(trend, unit, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MeasurementCell(trend: MeasurementTrend, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            trend.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            DataText(
                text = "${formatMeasurement(trend.value)} $unit",
                style = SpotterTheme.dataType.numeral,
                color = MaterialTheme.colorScheme.onSurface,
            )
            trend.delta?.takeIf { it != 0.0 }?.let { delta ->
                val sign = if (delta > 0) "+" else "−"
                Text(
                    "  $sign${formatMeasurement(kotlin.math.abs(delta))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Which series the Strength chart plots: the day's top weight, or the day's best estimated 1RM.
 * `internal` (with [StrengthMetricToggle] and [ChartCard]) so `ui/exercise` shares this exact
 * chart language rather than duplicating it.
 */
internal enum class StrengthMetric(val label: String) {
    WEIGHT("Weight"),
    EST_1RM("Est. 1RM"),
}

@Composable
private fun StrengthTab(
    trackedExercises: UiState<List<TrackedExercise>>,
    exerciseProgress: UiState<List<ExerciseProgressPoint>>,
    selectedExerciseId: String?,
    onSelectExercise: (String?) -> Unit,
    chartRange: ChartRange,
    onRangeSelect: (ChartRange) -> Unit,
    onRetry: () -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    val pulse = SpotterTheme.pulse
    var strengthMetric by remember { mutableStateOf(StrengthMetric.WEIGHT) }
    Column(modifier = Modifier.fillMaxSize()) {
        when (trackedExercises) {
            is UiState.Loading -> Box(
                Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = pulse.strength) }

            // Previously unhandled: an API failure here fell through to "Pick an exercise"
            // with no chips to pick. Show the failure with a retry instead.
            is UiState.Error -> EmptyState(
                icon = Icons.Default.FitnessCenter,
                title = "Couldn't load your lifts",
                subtitle = trackedExercises.message,
                action = { PulseButton(text = "Retry", onClick = onRetry) },
            )

            is UiState.Success -> {
                if (trackedExercises.data.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.FitnessCenter,
                        title = "No strength data yet",
                        subtitle = "Finish a few weighted workouts and your lifts will chart here.",
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(
                                horizontal = SpotterTheme.spacing.md,
                                vertical = SpotterTheme.spacing.sm,
                            ),
                        horizontalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
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
                                color = if (isSelected) pulse.strengthDim else pulse.panel,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) pulse.strength.copy(alpha = 0.4f) else pulse.hairline,
                                ),
                            ) {
                                Text(
                                    exercise.exerciseName,
                                    modifier = Modifier.padding(
                                        horizontal = SpotterTheme.spacing.md,
                                        vertical = 6.dp,
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) pulse.strength
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            else -> Unit
        }

        // The chart/detail pane only makes sense once there are chips to pick from — rendering
        // it alongside the list's own empty/error state stacked two full-size placeholders.
        val hasTracked = trackedExercises is UiState.Success && trackedExercises.data.isNotEmpty()
        if (!hasTracked) return@Column

        when (exerciseProgress) {
            is UiState.Loading -> LoadingState()

            is UiState.Success -> {
                val data = exerciseProgress.data
                if (data.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.FitnessCenter,
                        title = "No data for this range",
                        subtitle = "Try a wider window to see your progress.",
                    )
                } else {
                    // Est. 1RM only makes sense for weighted work; hide the toggle (and force
                    // Weight) for a purely bodyweight exercise that has no estimated 1RM.
                    val hasEst1rm = data.any { it.est1rm != null }
                    val metric = if (hasEst1rm) strengthMetric else StrengthMetric.WEIGHT
                    Column {
                        if (hasEst1rm) {
                            StrengthMetricToggle(
                                selected = strengthMetric,
                                onSelect = { strengthMetric = it },
                            )
                        }
                        RangeSelector(
                            selected = chartRange,
                            onSelect = onRangeSelect,
                            channel = pulse.strength,
                        )
                        val points = when (metric) {
                            StrengthMetric.WEIGHT -> data.mapNotNull { it.maxWeight?.toFloat() }
                            StrengthMetric.EST_1RM -> data.mapNotNull { it.est1rm?.toFloat() }
                        }
                        if (points.size >= 2) {
                            ChartCard {
                                LineChart(
                                    points = points,
                                    color = pulse.strength,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                )
                            }
                        }
                        LazyColumn {
                            items(data.reversed()) { point ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            horizontal = SpotterTheme.spacing.lg,
                                            vertical = SpotterTheme.spacing.sm,
                                        ),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(point.date, style = MaterialTheme.typography.bodyMedium)
                                    val valueText = when (metric) {
                                        StrengthMetric.WEIGHT ->
                                            "${point.maxReps} reps · " +
                                                weightUnit.formatWeightNullable(point.maxWeight)
                                        StrengthMetric.EST_1RM ->
                                            "1RM · " + weightUnit.formatWeightNullable(point.est1rm)
                                    }
                                    DataText(
                                        text = valueText,
                                        style = SpotterTheme.dataType.numeral,
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
                    EmptyState(
                        icon = Icons.Default.FitnessCenter,
                        title = "Pick an exercise",
                        subtitle = "Select one above to see its strength curve.",
                    )
                }
            }

            else -> Unit
        }
    }
}

@Composable
private fun RecordsTab(records: UiState<List<PersonalRecord>>) {
    val weightUnit = LocalWeightUnit.current
    val pulse = SpotterTheme.pulse
    when (records) {
        is UiState.Loading -> LoadingState()
        is UiState.Error -> ErrorState(message = records.message)
        is UiState.Success -> {
            if (records.data.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.EmojiEvents,
                    title = "No records yet",
                    subtitle = "Complete weighted sets to start banking personal records.",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(SpotterTheme.spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
                ) {
                    items(records.data) { pr ->
                        PanelCard(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.EmojiEvents,
                                    contentDescription = null,
                                    tint = pulse.strength,
                                    modifier = Modifier.padding(end = SpotterTheme.spacing.md),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(pr.exerciseName, style = MaterialTheme.typography.titleSmall)
                                    DataText(
                                        text = "${weightUnit.formatWeight(pr.maxWeight)} × ${pr.maxWeightReps}",
                                        style = SpotterTheme.dataType.numeralLarge,
                                        color = pulse.strength,
                                    )
                                    Text(
                                        "Est. 1RM ${weightUnit.formatWeight(pr.bestEst1rm)} · " +
                                            "Best volume ${weightUnit.formatWeight(pr.bestVolume)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        else -> Unit
    }
}

/** Frames a chart in a panel so it sits on a clean instrument surface with breathing room. */
@Composable
internal fun ChartCard(content: @Composable () -> Unit) {
    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpotterTheme.spacing.lg),
    ) { content() }
}

/**
 * The PULSE trend chart: a 2dp channel line over hairline gridlines with a soft gradient fill.
 * Only the latest point gets a dot (with a glow halo) — the line is the data, the dot is "now".
 * The curve rises into place on load.
 *
 * `internal` (not `private`) so screenshot tests can render the genuine component.
 */
@Composable
internal fun LineChart(
    points: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) return
    var play by remember(points) { mutableStateOf(false) }
    LaunchedEffect(points) { play = true }
    val anim by animateFloatAsState(
        targetValue = if (play) 1f else 0f,
        animationSpec = PulseMotion.data(),
        label = "chartReveal",
    )
    val gridColor = SpotterTheme.pulse.hairline
    Canvas(modifier = modifier) {
        val minVal = points.min()
        val maxVal = points.max()
        val range = (maxVal - minVal).coerceAtLeast(1f)
        val padX = 16.dp.toPx()
        val padY = 16.dp.toPx()
        val chartW = size.width - padX * 2
        val chartH = size.height - padY * 2
        val stepX = chartW / (points.size - 1)
        val baseline = padY + chartH

        fun xOf(i: Int) = padX + i * stepX
        fun yOf(v: Float): Float {
            val target = padY + chartH - ((v - minVal) / range) * chartH
            return baseline - anim * (baseline - target)
        }

        // Hairline gridlines — quiet reference, no axes.
        repeat(3) { i ->
            val y = padY + chartH * (i + 1) / 4f
            drawLine(
                color = gridColor,
                start = Offset(padX, y),
                end = Offset(padX + chartW, y),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // Filled area under the curve.
        val fill = Path().apply {
            moveTo(xOf(0), baseline)
            points.forEachIndexed { i, v -> lineTo(xOf(i), yOf(v)) }
            lineTo(xOf(points.size - 1), baseline)
            close()
        }
        drawPath(
            fill,
            brush = Brush.verticalGradient(
                colors = listOf(color.copy(alpha = 0.18f), color.copy(alpha = 0f)),
                startY = padY,
                endY = baseline,
            ),
        )

        // The line itself.
        val line = Path().apply {
            points.forEachIndexed { i, v ->
                if (i == 0) moveTo(xOf(i), yOf(v)) else lineTo(xOf(i), yOf(v))
            }
        }
        drawPath(
            line,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // "Now": the latest point only, with a glow halo.
        val last = points.size - 1
        drawCircle(
            color = color.copy(alpha = 0.22f),
            radius = 8.dp.toPx(),
            center = Offset(xOf(last), yOf(points[last])),
        )
        drawCircle(
            color = color,
            radius = 3.5.dp.toPx(),
            center = Offset(xOf(last), yOf(points[last])),
        )
    }
}

/** The six tape measurements, in the order they read down the body. */
private val MEASUREMENT_FIELDS = listOf(
    "Neck", "Chest", "Waist", "Hips", "Arm", "Thigh",
)

@Composable
private fun BodyweightLogDialog(
    onDismiss: () -> Unit,
    onConfirm: (BodyLogDraft) -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    val measureUnit = weightUnit.measurementLabel()
    var weightText by remember { mutableStateOf("") }
    var bodyfatText by remember { mutableStateOf("") }
    var showMeasurements by remember { mutableStateOf(false) }
    // Keyed by field name; only non-blank, parseable entries are sent.
    val measurements = remember { mutableStateMapOf<String, String>() }

    fun decimal(input: String) = input.filter { c -> c.isDigit() || c == '.' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log bodyweight") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
            ) {
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = decimal(it) },
                    label = { Text(weightUnit.formatWeightFieldLabel()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = bodyfatText,
                    onValueChange = { bodyfatText = decimal(it) },
                    label = { Text("Body fat % (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = { showMeasurements = !showMeasurements },
                    modifier = Modifier.align(Alignment.Start),
                ) {
                    Icon(
                        if (showMeasurements) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.padding(end = SpotterTheme.spacing.xs),
                    )
                    Text(if (showMeasurements) "Hide measurements" else "Add measurements")
                }
                if (showMeasurements) {
                    MEASUREMENT_FIELDS.forEach { field ->
                        OutlinedTextField(
                            value = measurements[field].orEmpty(),
                            onValueChange = { measurements[field] = decimal(it) },
                            label = { Text("$field ($measureUnit)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val weight = weightText.toDoubleOrNull() ?: return@TextButton
                    fun field(name: String) = measurements[name]?.toDoubleOrNull()
                    onConfirm(
                        BodyLogDraft(
                            weight = weight,
                            bodyfat = bodyfatText.toDoubleOrNull(),
                            neck = field("Neck"),
                            chest = field("Chest"),
                            waist = field("Waist"),
                            hips = field("Hips"),
                            arm = field("Arm"),
                            thigh = field("Thigh"),
                        )
                    )
                },
                enabled = weightText.toDoubleOrNull() != null,
            ) { Text("Log") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
