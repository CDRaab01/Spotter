package com.spotter.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import design.pulse.ui.components.PulseButton
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    navController: NavController,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val step by viewModel.currentStep.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val total = viewModel.totalSteps

    LaunchedEffect(Unit) {
        viewModel.navigateToHome.collect {
            navController.navigate(Screen.Home.route) { popUpTo(0) { inclusive = true } }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Step $step of $total") },
                navigationIcon = {
                    if (step > 1) {
                        IconButton(onClick = { viewModel.prevStep() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LinearProgressIndicator(
                progress = { step.toFloat() / total },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = SpotterTheme.pulse.effort,
                trackColor = SpotterTheme.pulse.hairline,
            )

            Spacer(Modifier.height(8.dp))

            when (step) {
                1 -> OptionStep(
                    question = "What's your training experience?",
                    options = listOf(
                        "BEGINNER" to "Beginner — under 1 year",
                        "INTERMEDIATE" to "Intermediate — 1–3 years",
                        "ADVANCED" to "Advanced — 3+ years",
                    ),
                    selected = draft.experience,
                    onSelect = { viewModel.setExperience(it) },
                )
                2 -> OptionStep(
                    question = "What's your primary goal?",
                    options = listOf(
                        "MUSCLE" to "Build muscle",
                        "FAT_LOSS" to "Lose fat",
                        "STRENGTH" to "Increase strength",
                        "FITNESS" to "General fitness",
                    ),
                    selected = draft.goal,
                    onSelect = { viewModel.setGoal(it) },
                )
                3 -> OptionStepWithOther(
                    question = "What equipment do you have?",
                    options = listOf(
                        "BARBELL" to "Barbell & plates (home gym)",
                        "DUMBBELLS" to "Dumbbells only",
                        "FULL_GYM" to "Full commercial gym",
                        "BODYWEIGHT" to "Bodyweight only",
                    ),
                    selected = draft.equipment,
                    onSelect = { viewModel.setEquipment(it) },
                )
                4 -> OptionStep(
                    question = "What's your age group?",
                    options = listOf(
                        "13_17" to "13–17",
                        "18_24" to "18–24",
                        "25_34" to "25–34",
                        "35_44" to "35–44",
                        "45_54" to "45–54",
                        "55_64" to "55–64",
                        "65_PLUS" to "65+",
                    ),
                    selected = draft.ageGroup,
                    onSelect = { viewModel.setAgeGroup(it) },
                )
                5 -> LimitationsStep(
                    selected = draft.limitations,
                    onSelect = { viewModel.setLimitations(it) },
                )
            }

            Spacer(Modifier.height(8.dp))

            val canContinue = when (step) {
                1 -> draft.experience.isNotBlank()
                2 -> draft.goal.isNotBlank()
                3 -> draft.equipment.isNotBlank()
                4 -> draft.ageGroup.isNotBlank()
                5 -> true
                else -> false
            }

            PulseButton(
                text = if (step == total) "Let's go" else "Continue",
                onClick = { viewModel.nextStep() },
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OptionStep(
    question: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(question, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
    options.forEach { (value, label) ->
        OptionCard(
            label = label,
            selected = selected == value,
            onClick = { onSelect(value) },
        )
    }
}

@Composable
private fun OptionStepWithOther(
    question: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val presets = options.map { it.first }.toSet()
    // A non-preset, non-blank draft value means we're resuming on the "Other" choice
    // (e.g. after navigating back to this step).
    val startedOnOther = selected.isNotBlank() && selected !in presets
    // Track the "Other" selection explicitly rather than inferring it from the draft
    // value: tapping "Other" before typing leaves the value blank, and a blank value
    // is indistinguishable from "nothing selected".
    var otherSelected by remember { mutableStateOf(startedOnOther) }
    var otherText by remember { mutableStateOf(if (startedOnOther) selected else "") }

    Text(question, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
    options.forEach { (value, label) ->
        OptionCard(
            label = label,
            selected = !otherSelected && selected == value,
            onClick = {
                otherSelected = false
                onSelect(value)
            },
        )
    }
    OptionCard(
        label = "Other / write your own",
        selected = otherSelected,
        onClick = {
            otherSelected = true
            onSelect(otherText)
        },
    )
    if (otherSelected) {
        OutlinedTextField(
            value = otherText,
            onValueChange = { otherText = it; onSelect(it) },
            placeholder = { Text("Describe your equipment…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            maxLines = 3,
        )
    }
}

@Composable
private fun LimitationsStep(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val presets = listOf(
        "Lower back" to "Lower back",
        "Shoulders" to "Shoulders",
        "Knees" to "Knees",
        "None" to "No limitations",
    )
    val selectedSet = selected.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
    var customText by remember { mutableStateOf("") }

    Text("Any injuries or limitations?", style = MaterialTheme.typography.titleLarge)
    Text(
        "Select all that apply — or skip if you have none.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    presets.forEach { (value, label) ->
        OptionCard(
            label = label,
            selected = value in selectedSet || (value == "None" && selectedSet.isEmpty()),
            onClick = {
                if (value == "None") {
                    onSelect("")
                } else {
                    if (value in selectedSet) selectedSet.remove(value)
                    else { selectedSet.remove("None"); selectedSet.add(value) }
                    onSelect(selectedSet.joinToString(", "))
                }
            },
        )
    }
    OutlinedTextField(
        value = customText,
        onValueChange = {
            customText = it
            val base = selectedSet.filter { s -> presets.any { p -> p.first == s } }.joinToString(", ")
            onSelect(if (it.isBlank()) base else if (base.isBlank()) it else "$base, $it")
        },
        placeholder = { Text("Other details (optional)…") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        maxLines = 3,
    )
}

@Composable
private fun OptionCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val pulse = SpotterTheme.pulse
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) pulse.effortDim else pulse.panel,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            1.dp,
            if (selected) pulse.effort.copy(alpha = 0.45f) else pulse.hairline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = pulse.effort,
                )
            }
        }
    }
}
