package com.spotter.ui.program

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.ui.components.EmptyState
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.formatWeight
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

/**
 * Day-by-day preview of a preset program before committing to it: what each day trains, at what
 * sets × reps and starting load, and where the rest days fall. Two ways out — make it the active
 * program, or just save it alongside whatever is already running (`activate = false`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramPresetDetailScreen(
    presetId: String,
    navController: NavController,
    viewModel: ProgramPresetsViewModel = hiltViewModel(),
) {
    val preset = ProgramPresets.byId(presetId)
    val applyingId by viewModel.applyingId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.applied.collect { result ->
            snackbarHostState.showSnackbar(presetAppliedMessage(result))
            navController.popBackStack()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.error.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(preset?.displayName ?: "Preset") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (preset == null) {
            EmptyState(
                icon = Icons.Default.SearchOff,
                title = "Preset not found",
                subtitle = "It may have been renamed in a newer version of the app.",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        val isApplying = applyingId == preset.id
        val spacing = SpotterTheme.spacing
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item {
                PanelCard(modifier = Modifier.fillMaxWidth()) {
                    Text(preset.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(spacing.sm))
                    Text(
                        presetCadenceLine(preset),
                        style = MaterialTheme.typography.labelMedium,
                        color = SpotterTheme.pulse.effort,
                    )
                }
            }

            item { SectionHeader(label = "The cycle", channel = SpotterTheme.pulse.effort) }

            itemsIndexedDays(preset)

            item {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    if (isApplying) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = SpotterTheme.pulse.effort,
                            )
                        }
                    }
                    PulseButton(
                        text = "Add & activate",
                        onClick = { viewModel.applyPreset(preset, activate = true) },
                        enabled = applyingId == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PulseButton(
                        text = "Add without activating",
                        onClick = { viewModel.applyPreset(preset, activate = false) },
                        enabled = applyingId == null,
                        tonal = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Activating replaces your current active program. Adding without " +
                            "activating keeps today's schedule as it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The day cards, numbered through the cycle so rest days read as part of the prescription. */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedDays(preset: PresetProgram) {
    preset.days.forEachIndexed { index, day ->
        item(key = "day-$index") { PresetDayCard(index = index, day = day) }
    }
}

@Composable
private fun PresetDayCard(index: Int, day: PresetDay) {
    val weightUnit = LocalWeightUnit.current
    val pulse = SpotterTheme.pulse
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        channel = if (day.isRest) pulse.recovery else null,
    ) {
        Text(
            "Day ${index + 1} · ${day.label}",
            style = MaterialTheme.typography.titleSmall,
            color = if (day.isRest) pulse.recovery else MaterialTheme.colorScheme.onSurface,
        )
        if (day.isRest) {
            Text(
                "Rest — no session scheduled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@PanelCard
        }
        Spacer(Modifier.height(SpotterTheme.spacing.sm))
        day.exercises.forEach { ex ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ex.name,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                DataText(
                    text = "${ex.sets}×${ex.reps} " +
                        (ex.weight?.let { weightUnit.formatWeight(it) } ?: "BW"),
                    style = SpotterTheme.dataType.numeral,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
