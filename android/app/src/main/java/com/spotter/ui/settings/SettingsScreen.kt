package com.spotter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.ui.components.PulsingDots
import com.spotter.ui.components.SectionHeader
import com.spotter.ui.components.SpotterCard
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.SpotterTheme
import com.spotter.util.DarkModePreference
import com.spotter.util.DistanceUnit
import com.spotter.util.UiState
import com.spotter.util.WeightUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val userState by viewModel.user.collectAsState()
    val darkMode by viewModel.darkMode.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    val distanceUnit by viewModel.distanceUnit.collectAsState()
    val cadenceDays by viewModel.workoutCadenceDays.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val resetting by viewModel.resetting.collectAsState()
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.navigateToLogin.collect {
            navController.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.serverUrlMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.resetError.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { if (!resetting) showResetDialog = false },
            title = { Text("Reset account?") },
            text = {
                Text(
                    "This permanently deletes all your workouts, sessions, progress, " +
                        "programs, and chat history. Your account and login are kept. " +
                        "You'll be signed out and asked to set up again.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.resetAccount() },
                    enabled = !resetting,
                ) {
                    Text(
                        if (resetting) "Resetting…" else "Reset",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showResetDialog = false },
                    enabled = !resetting,
                ) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Profile header
            when (val state = userState) {
                is UiState.Loading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { PulsingDots() }

                is UiState.Error -> Text(
                    state.message,
                    color = MaterialTheme.colorScheme.error,
                )

                is UiState.Success -> ProfileHeader(
                    name = state.data.name,
                    email = state.data.email,
                )

                else -> Unit
            }

            SettingsSection("Appearance") {
                SegmentedSettingRow(
                    label = "Theme",
                    options = DarkModePreference.entries,
                    selected = darkMode,
                    labelFor = {
                        when (it) {
                            DarkModePreference.SYSTEM -> "System"
                            DarkModePreference.LIGHT -> "Light"
                            DarkModePreference.DARK -> "Dark"
                        }
                    },
                    onSelect = { viewModel.setDarkMode(it) },
                )
            }

            SettingsSection("Units") {
                SegmentedSettingRow(
                    label = "Weight",
                    options = WeightUnit.entries,
                    selected = weightUnit,
                    labelFor = { if (it == WeightUnit.KG) "kg" else "lbs" },
                    onSelect = { viewModel.setWeightUnit(it) },
                )
                Spacer(Modifier.height(8.dp))
                SegmentedSettingRow(
                    label = "Distance",
                    options = DistanceUnit.entries,
                    selected = distanceUnit,
                    labelFor = { if (it == DistanceUnit.KM) "km" else "mi" },
                    onSelect = { viewModel.setDistanceUnit(it) },
                )
            }

            SettingsSection("Schedule") {
                CadenceStepper(
                    cadenceDays = cadenceDays,
                    onChange = { viewModel.setWorkoutCadenceDays(it) },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "How often upcoming workouts are spaced on Home and the calendar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection("Server") {
                var serverUrlInput by remember(serverUrl) { mutableStateOf(serverUrl) }
                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = { serverUrlInput = it },
                    label = { Text("Server URL") },
                    supportingText = {
                        Text("e.g. http://100.x.y.z:8000/ (Tailscale) or https://spotter.example.com/")
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.setServerUrl(serverUrlInput) },
                    enabled = serverUrlInput.isNotBlank() && serverUrlInput != serverUrl,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save server URL")
                }
            }

            SettingsSection("Account") {
                Button(
                    onClick = { viewModel.logout() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Sign out")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showResetDialog = true },
                    enabled = !resetting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Reset account")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Erase all your data and start fresh. Your login is kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Profile card: a gradient initial avatar next to the user's name + email. */
@Composable
private fun ProfileHeader(name: String, email: String) {
    SpotterCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(SpotterTheme.brand.heroGradient, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.trim().take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(
                    email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A titled settings group rendered on a [SpotterCard] with an accent [SectionHeader]. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    SpotterCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun CadenceStepper(
    cadenceDays: Int,
    onChange: (Int) -> Unit,
    minDays: Int = 1,
    maxDays: Int = 14,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Train every",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = { onChange((cadenceDays - 1).coerceAtLeast(minDays)) },
            enabled = cadenceDays > minDays,
        ) { Text("−") }
        Text(
            text = "$cadenceDays ${if (cadenceDays == 1) "day" else "days"}",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
        OutlinedButton(
            onClick = { onChange((cadenceDays + 1).coerceAtMost(maxDays)) },
            enabled = cadenceDays < maxDays,
        ) { Text("+") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Enum<T>> SegmentedSettingRow(
    label: String,
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    label = { Text(labelFor(option)) },
                )
            }
        }
    }
}
