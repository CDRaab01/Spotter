package com.spotter.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.ui.components.PulsingDots
import com.spotter.ui.theme.SpotterTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.PulseSegmentedControl
import design.pulse.ui.components.PulseStepperRow
import design.pulse.ui.components.PulseSwitchRow
import design.pulse.ui.components.SettingsSection

/** Everything the equipment editor can do; defaulted to no-ops for screenshot fixtures. */
data class EquipmentActions(
    val onSetUnit: (String) -> Unit = {},
    val onToggleBar: (Double) -> Unit = {},
    val onSetPlatePairs: (Double, Int) -> Unit = { _, _ -> },
    val onToggleDumbbell: (Double) -> Unit = {},
    val onSetDumbbells: (List<Double>) -> Unit = {},
    val onSetStackStep: (Double?) -> Unit = {},
    val onSave: () -> Unit = {},
    val onReset: () -> Unit = {},
)

/**
 * Settings → Workout → My equipment. Navigation, back-guard and toasts only; everything rendered
 * is [EquipmentContent], which is stateless so screenshot tests drive the real screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EquipmentScreen(
    navController: NavController,
    viewModel: EquipmentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val leave = { if (state.dirty) confirmDiscard = true else navController.popBackStack() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Your equipment edits haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; navController.popBackStack() }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My equipment") },
                navigationIcon = {
                    IconButton(onClick = { leave() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        EquipmentContent(
            state = state,
            actions = EquipmentActions(
                onSetUnit = viewModel::setUnit,
                onToggleBar = viewModel::toggleBar,
                onSetPlatePairs = viewModel::setPlatePairs,
                onToggleDumbbell = viewModel::toggleDumbbell,
                onSetDumbbells = viewModel::setDumbbells,
                onSetStackStep = viewModel::setStackStep,
                onSave = viewModel::save,
                onReset = viewModel::resetToDefault,
            ),
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun EquipmentContent(
    state: EquipmentUiState,
    actions: EquipmentActions,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Column(modifier.fillMaxSize().padding(32.dp)) { PulsingDots() }
        return
    }
    val inv = state.draft
    val u = inv.unit
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Suggested weights only use loads you can make with this. A barbell goes up by a " +
                "plate on each side, so the smallest jump is two of your smallest plate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.configured) {
            Text(
                "Not set yet — Spotter is assuming a standard gym (below). Change anything " +
                    "that's different and save.",
                style = MaterialTheme.typography.bodySmall,
                color = SpotterTheme.pulse.streak,
            )
        }

        SettingsSection("Units") {
            PulseSegmentedControl(
                options = listOf("lb", "kg"),
                selectedIndex = if (u == "kg") 1 else 0,
                onSelect = { actions.onSetUnit(if (it == 1) "kg" else "lb") },
            )
            Spacer(Modifier.height(6.dp))
            Hint("The unit your plates are stamped in. Switching starts from that unit's standard set.")
        }

        SettingsSection("Barbells") {
            ChipFlow(
                values = EquipmentOptions.merged(EquipmentOptions.bars(u), inv.bars, descending = true),
                selected = inv.bars,
                unit = u,
                onToggle = actions.onToggleBar,
            )
            Spacer(Modifier.height(6.dp))
            Hint("Pick every bar you load — an EZ or light bar keeps small curl loads possible.")
        }

        SettingsSection("Plates") {
            Hint("How many pairs of each you own — one of each pair goes on each side.")
            Spacer(Modifier.height(4.dp))
            EquipmentOptions.merged(EquipmentOptions.plates(u), inv.plates.map { it.weight }, descending = true)
                .forEach { w ->
                    PulseStepperRow(
                        label = "${EquipmentOptions.num(w)} $u",
                        value = EquipmentOptions.pairsFor(inv, w),
                        onValueChange = { actions.onSetPlatePairs(w, it) },
                        min = 0,
                        max = EquipmentViewModel.MAX_PAIRS,
                        valueLabel = { n -> if (n == 0) "none" else "$n ${if (n == 1) "pair" else "pairs"}" },
                    )
                }
            Spacer(Modifier.height(6.dp))
            val jump = EquipmentOptions.smallestBarJump(inv)
            Text(
                if (jump == null) "No plates — barbell loads are just the bar."
                else "Smallest barbell jump: ${EquipmentOptions.num(jump)} $u " +
                    "(${EquipmentOptions.num(jump / 2)} per side).",
                style = MaterialTheme.typography.bodyMedium,
                color = SpotterTheme.pulse.effort,
            )
        }

        SettingsSection("Dumbbells") {
            val options = EquipmentOptions.merged(EquipmentOptions.dumbbells(u), inv.dumbbells)
            ChipFlow(values = options, selected = inv.dumbbells, unit = null, onToggle = actions.onToggleDumbbell)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { actions.onSetDumbbells(options) }) { Text("Select all") }
                TextButton(onClick = { actions.onSetDumbbells(emptyList()) }) { Text("Clear") }
            }
            Hint("Per-hand weights you have: ${EquipmentOptions.dumbbellSummary(inv.dumbbells, u)}.")
        }

        SettingsSection("Machines & cables") {
            PulseSwitchRow(
                title = "Weight-stack machines or cables",
                subtitle = "They move by the pin, not by plates.",
                checked = inv.stackStep != null,
                onCheckedChange = { on ->
                    actions.onSetStackStep(if (on) EquipmentOptions.stackSteps(u)[1] else null)
                },
            )
            inv.stackStep?.let { step ->
                Spacer(Modifier.height(6.dp))
                Caption("Stack step")
                Spacer(Modifier.height(4.dp))
                ChipFlow(
                    values = EquipmentOptions.merged(EquipmentOptions.stackSteps(u), listOf(step)),
                    selected = listOf(step),
                    unit = u,
                    onToggle = { actions.onSetStackStep(it) },
                )
            }
        }

        Button(
            onClick = actions.onSave,
            enabled = state.dirty && !state.saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.saving) "Saving…" else "Save equipment")
        }
        if (state.configured) {
            TextButton(
                onClick = actions.onReset,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset to a standard gym") }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Multi-select weight chips that wrap to their content (see `ChipGroup` for why not fixed rows). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(
    values: List<Double>,
    selected: List<Double>,
    unit: String?,
    onToggle: (Double) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        values.forEach { w ->
            FilterChip(
                selected = w in selected,
                onClick = { onToggle(w) },
                label = {
                    Text(
                        EquipmentOptions.num(w) + (unit?.let { " $it" } ?: ""),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}
