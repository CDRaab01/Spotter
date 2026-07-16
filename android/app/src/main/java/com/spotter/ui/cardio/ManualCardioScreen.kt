package com.spotter.ui.cardio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.label
import com.spotter.ui.theme.parseToMeters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualCardioScreen(
    navController: NavController,
    viewModel: ManualCardioViewModel = hiltViewModel(),
) {
    val pulse = SpotterTheme.pulse
    val distanceUnit by viewModel.distanceUnit.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var isRun by remember { mutableStateOf(true) }
    var minutes by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var showDatePicker by remember { mutableStateOf(false) }

    // On success, pop back to the Cardio home so the entry shows in history.
    LaunchedEffect(saveState) {
        when (val s = saveState) {
            is ManualCardioSaveState.Saved -> navController.popBackStack()
            is ManualCardioSaveState.Error -> {
                snackbar.showSnackbar(s.message)
                viewModel.clearError()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log cardio") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
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
                    selected = !isRun,
                    onClick = { isRun = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Walk") }
                SegmentedButton(
                    selected = isRun,
                    onClick = { isRun = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Run") }
            }

            PanelCard(Modifier.fillMaxWidth(), channel = pulse.recovery) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { new -> minutes = new.filter { it.isDigit() }.take(4) },
                    label = { Text("Duration (minutes)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(SpotterTheme.spacing.md))
                OutlinedTextField(
                    value = distance,
                    onValueChange = { new ->
                        distance = new.filter { it.isDigit() || it == '.' }.take(7)
                    },
                    label = { Text("Distance (${distanceUnit.label()}) — optional") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(SpotterTheme.spacing.md))
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Date: ${CardioFormat.longDate(date)}")
                }
            }

            Text(
                "Logged walks and runs count toward your streak and active minutes, just like a guided run.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(SpotterTheme.spacing.sm))
            PulseButton(
                text = "Save entry",
                onClick = {
                    val mins = minutes.toIntOrNull() ?: 0
                    val meters = distanceUnit.parseToMeters(distance)
                    viewModel.save(
                        isRun = isRun,
                        durationMinutes = mins,
                        distanceMeters = meters,
                        date = date,
                    )
                },
                enabled = saveState !is ManualCardioSaveState.Saving && minutes.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                channel = pulse.recovery,
                onChannel = pulse.onRecovery,
                gradient = androidx.compose.ui.graphics.SolidColor(pulse.recovery),
            )
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        // Never allow a future date — you can't have already done a future run.
                        date = if (picked.isAfter(LocalDate.now())) LocalDate.now() else picked
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
