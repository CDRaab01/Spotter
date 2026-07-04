package com.spotter.ui.cardio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.SpotterTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeRunConfigScreen(
    navController: NavController,
    viewModel: FreeRunConfigViewModel = hiltViewModel(),
) {
    val pulse = SpotterTheme.pulse
    var openEnded by remember { mutableStateOf(true) }
    var warmUp by remember { mutableIntStateOf(5) }
    var runMin by remember { mutableIntStateOf(2) }
    var walkMin by remember { mutableIntStateOf(1) }
    var repeats by remember { mutableIntStateOf(6) }
    var coolDown by remember { mutableIntStateOf(5) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Free Run") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(SpotterTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.lg),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = openEnded,
                    onClick = { openEnded = true },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Open-ended") }
                SegmentedButton(
                    selected = !openEnded,
                    onClick = { openEnded = false },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Custom intervals") }
            }

            if (openEnded) {
                PanelCard(Modifier.fillMaxWidth(), channel = pulse.recovery) {
                    Text("Open run", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A single continuous run that counts up. Tap Finish whenever you're done.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = SpotterTheme.spacing.xs),
                    )
                }
            } else {
                PanelCard(Modifier.fillMaxWidth()) {
                    Stepper("Warm-up (min)", warmUp, 0..30) { warmUp = it }
                    Stepper("Run (min)", runMin, 1..60) { runMin = it }
                    Stepper("Walk (min)", walkMin, 0..30) { walkMin = it }
                    Stepper("Repeats", repeats, 1..20) { repeats = it }
                    Stepper("Cool-down (min)", coolDown, 0..30) { coolDown = it }
                    val total = warmUp * 60 + repeats * (runMin + walkMin) * 60 + coolDown * 60
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = SpotterTheme.spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Total", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DataText(
                            text = CardioFormat.minutesLabel(total),
                            style = SpotterTheme.dataType.numeralLarge,
                            color = pulse.recovery,
                        )
                    }
                }
            }

            Spacer(Modifier.size(SpotterTheme.spacing.sm))
            PulseButton(
                text = "Start run",
                onClick = {
                    if (openEnded) viewModel.startOpenEnded()
                    else viewModel.startCustom(warmUp, runMin, walkMin, repeats, coolDown)
                    navController.navigate(Screen.CardioRun.route)
                },
                modifier = Modifier.fillMaxWidth(),
                channel = pulse.recovery,
                onChannel = pulse.onRecovery,
                gradient = androidx.compose.ui.graphics.SolidColor(pulse.recovery),
            )
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpotterTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        IconButton(
            onClick = { if (value > range.first) onChange(value - 1) },
            enabled = value > range.first,
        ) { Icon(Icons.Filled.Remove, contentDescription = "Decrease $label") }
        DataText(
            text = "$value",
            style = SpotterTheme.dataType.dataSmall,
            modifier = Modifier.size(width = 40.dp, height = 28.dp),
        )
        IconButton(
            onClick = { if (value < range.last) onChange(value + 1) },
            enabled = value < range.last,
        ) { Icon(Icons.Filled.Add, contentDescription = "Increase $label") }
    }
}
