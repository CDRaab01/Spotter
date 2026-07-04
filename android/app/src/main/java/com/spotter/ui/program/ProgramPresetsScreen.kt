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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import design.pulse.ui.components.SectionHeader
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import com.spotter.ui.theme.SpotterTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramPresetsScreen(
    navController: NavController,
    viewModel: ProgramPresetsViewModel = hiltViewModel(),
) {
    val applyingId by viewModel.applyingId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.applied.collect { name ->
            snackbarHostState.showSnackbar("\"$name\" added & activated")
            navController.popBackStack()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.error.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preset programs") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionHeader("Pick a starting point")
            }
            items(ProgramPresets.all, key = { it.id }) { preset ->
                PresetCard(
                    preset = preset,
                    isApplying = applyingId == preset.id,
                    enabled = applyingId == null,
                    onApply = { viewModel.applyPreset(preset) },
                )
            }
        }
    }
}

@Composable
private fun PresetCard(
    preset: PresetProgram,
    isApplying: Boolean,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(preset.displayName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                preset.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            preset.days.forEach { day ->
                Text(
                    "${day.label}: ${day.exercises.joinToString(", ") { it.name }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isApplying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = SpotterTheme.pulse.effort,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                PulseButton(
                    text = "Use program",
                    onClick = onApply,
                    enabled = enabled,
                    tonal = true,
                    compact = true,
                )
            }
        }
    }
}
