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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
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
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                modifier = Modifier.fillMaxWidth(),
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
                        "UNDER_30" to "Under 30",
                        "30_40" to "30–40",
                        "OVER_40" to "Over 40",
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

            Button(
                onClick = { viewModel.nextStep() },
                enabled = canContinue,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (step == total) "Let's go" else "Continue")
            }
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
    val isOther = selected.isNotBlank() && selected !in presets
    var otherText by remember(isOther) { mutableStateOf(if (isOther) selected else "") }

    Text(question, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(4.dp))
    options.forEach { (value, label) ->
        OptionCard(
            label = label,
            selected = selected == value,
            onClick = { onSelect(value) },
        )
    }
    OptionCard(
        label = "Other / write your own",
        selected = isOther,
        onClick = { onSelect(otherText.ifBlank { " " }) },
    )
    if (isOther) {
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
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
