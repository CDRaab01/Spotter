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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.export.ExportKind
import com.spotter.data.export.ExportedFile
import com.spotter.health.HealthConnectManager
import com.spotter.ui.components.PulsingDots
import design.pulse.ui.components.SectionHeader
import design.pulse.ui.components.PanelCard
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
    val trackRpe by viewModel.trackRpe.collectAsState()
    val autoStartRest by viewModel.autoStartRest.collectAsState()
    val nudgeEnabled by viewModel.workoutNudgeEnabled.collectAsState()
    val quietStartHour by viewModel.quietStartHour.collectAsState()
    val quietEndHour by viewModel.quietEndHour.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val programs by viewModel.programs.collectAsState()
    val resetting by viewModel.resetting.collectAsState()
    val serverVersion by viewModel.serverVersion.collectAsState()
    val exporting by viewModel.exporting.collectAsState()
    val healthConnectEnabled by viewModel.healthConnectEnabled.collectAsState()
    val profileDraft by viewModel.profileDraft.collectAsState()
    val profileSaving by viewModel.profileSaving.collectAsState()
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    // Health Connect asks for its write permissions through its own contract (it needs an
    // Activity, so the VM only signals *when* to ask and consumes the result).
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        contract = remember { viewModel.healthPermissionContract() },
        onResult = { granted -> viewModel.onHealthPermissionsResult(granted) },
    )

    LaunchedEffect(Unit) {
        viewModel.requestHealthPermissions.collect { permissions ->
            healthPermissionLauncher.launch(permissions)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.healthMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.profileMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.exportReady.collect { exported -> shareExport(context, exported) }
    }

    LaunchedEffect(Unit) {
        viewModel.exportError.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

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

    LaunchedEffect(Unit) {
        viewModel.navigateToOnboarding.collect {
            navController.navigate(Screen.Onboarding.route) { popUpTo(0) { inclusive = true } }
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
                        "You'll be asked to set up again.",
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

            SettingsSection("Training profile") {
                Text(
                    "Your coach reads this before every reply — keep the equipment line current " +
                        "and it won't have to ask again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                // Equipment leads: it's the thing that actually changes (new gym, new dumbbells)
                // and the thing the coach was forgetting.
                OutlinedTextField(
                    value = profileDraft.equipment,
                    onValueChange = { viewModel.setProfileEquipment(it) },
                    label = { Text("Equipment") },
                    supportingText = {
                        Text("e.g. dumbbells up to 50lb, pull-up bar, bands")
                    },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                ChoiceRow(
                    label = "Experience",
                    options = TrainingProfileOptions.EXPERIENCE,
                    selected = profileDraft.experience,
                    onSelect = { viewModel.setProfileExperience(it) },
                )

                Spacer(Modifier.height(12.dp))
                ChoiceRow(
                    label = "Goal",
                    options = TrainingProfileOptions.GOAL,
                    selected = profileDraft.goal,
                    onSelect = { viewModel.setProfileGoal(it) },
                )

                Spacer(Modifier.height(12.dp))
                ChoiceRow(
                    label = "Age group",
                    options = TrainingProfileOptions.AGE_GROUP,
                    selected = profileDraft.ageGroup,
                    onSelect = { viewModel.setProfileAgeGroup(it) },
                    perRow = 4,
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = profileDraft.limitations,
                    onValueChange = { viewModel.setProfileLimitations(it) },
                    label = { Text("Limitations / injuries") },
                    supportingText = { Text("e.g. lower back, left shoulder — leave blank if none") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.saveProfile() },
                    enabled = !profileSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (profileSaving) "Saving…" else "Save training profile")
                }
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

            SettingsSection("Workout") {
                SwitchSettingRow(
                    title = "Track RPE",
                    subtitle = "Completed sets show a 1–10 effort entry (one decimal).",
                    checked = trackRpe,
                    onCheckedChange = { viewModel.setTrackRpe(it) },
                )
                Spacer(Modifier.height(8.dp))
                SwitchSettingRow(
                    title = "Auto-start rest timer",
                    subtitle = "Completing a set starts the rest countdown. Off = a Start rest " +
                        "button appears instead.",
                    checked = autoStartRest,
                    onCheckedChange = { viewModel.setAutoStartRest(it) },
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

            SettingsSection("Reminders") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Workout nudges", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "A ${formatHour(com.spotter.util.AppPreferences.NUDGE_HOUR)} reminder on " +
                                "workout days, a ${formatHour(com.spotter.util.AppPreferences.EVENING_NUDGE_HOUR)} " +
                                "streak-saver if the day is slipping, and a one-off comeback nudge " +
                                "after a few missed days. Never on rest days.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = nudgeEnabled,
                        onCheckedChange = { viewModel.setWorkoutNudgeEnabled(it) },
                    )
                }
                if (nudgeEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Quiet hours",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HourStepper(
                            hour = quietStartHour,
                            onChange = { viewModel.setQuietHours(it, quietEndHour) },
                        )
                        Text(
                            "  to  ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HourStepper(
                            hour = quietEndHour,
                            onChange = { viewModel.setQuietHours(quietStartHour, it) },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The nudge is skipped if it would land inside this window.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsSection("Programs") {
                if (programs.isEmpty()) {
                    Text(
                        "No programs yet. Ask the AI coach for a multi-day program, or start " +
                            "from a preset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate(Screen.Programs.route) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Browse programs & presets",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    programs.forEach { program ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    navController.navigate(Screen.ProgramDetail.createRoute(program.id))
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                program.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (program.isActive) {
                                Text(
                                    "Active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = SpotterTheme.pulse.effort,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            SettingsSection("Library & data") {
                NavRow(
                    icon = { Icon(Icons.Default.FitnessCenter, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    label = "Exercise library",
                    onClick = { navController.navigate(Screen.ExerciseLibrary.route) },
                )
                NavRow(
                    icon = { Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    label = "Workout history",
                    onClick = { navController.navigate(Screen.SessionHistory.route) },
                )
            }

            SettingsSection("Export data") {
                Text(
                    "Download your own copy. Files are handed straight to the share sheet — " +
                        "save them, mail them, drop them in a spreadsheet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                ExportRow(
                    icon = Icons.Default.Description,
                    label = "Workout data (JSON)",
                    busy = exporting == ExportKind.JSON,
                    enabled = exporting == null,
                    onClick = { viewModel.export(ExportKind.JSON) },
                )
                ExportRow(
                    icon = Icons.Default.TableChart,
                    label = "Sets (CSV)",
                    busy = exporting == ExportKind.CSV,
                    enabled = exporting == null,
                    onClick = { viewModel.export(ExportKind.CSV) },
                )
            }

            SettingsSection("Health Connect") {
                val available =
                    viewModel.healthConnectAvailability == HealthConnectManager.Availability.AVAILABLE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Sync to Health Connect", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            when (viewModel.healthConnectAvailability) {
                                HealthConnectManager.Availability.AVAILABLE ->
                                    "Finished workouts and weigh-ins are copied to Health Connect. " +
                                        "Spotter only writes — it never reads your health data."
                                HealthConnectManager.Availability.UPDATE_REQUIRED ->
                                    "Update Health Connect on this device to turn this on."
                                HealthConnectManager.Availability.UNAVAILABLE ->
                                    "Health Connect isn't available on this device."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = healthConnectEnabled && available,
                        enabled = available,
                        onCheckedChange = { viewModel.setHealthConnectEnabled(it) },
                    )
                }
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
                    // Tonal error treatment: white on the saturated error red is only ~3:1; the
                    // errorContainer/onErrorContainer pair is legible (11:1) and still reads as a
                    // destructive action.
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
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

            SettingsSection("About") {
                VersionRow("App", viewModel.appVersion)
                Spacer(Modifier.height(6.dp))
                val serverValue = when (val v = serverVersion) {
                    is UiState.Success -> {
                        val info = v.data
                        if (info.commit.isBlank() || info.commit == "unknown") info.version
                        else "${info.version} · ${info.commit}"
                    }
                    is UiState.Error -> "Unavailable"
                    else -> "Checking…"
                }
                VersionRow("Server", serverValue)
                (serverVersion as? UiState.Success)?.data?.builtAt
                    ?.takeIf { it.isNotBlank() && it != "unknown" }
                    ?.let { builtAt ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Deployed $builtAt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
    }
}

/**
 * Hands a finished export to the Android share sheet via the app's FileProvider — the only way a
 * cache file can leave the app sandbox. The read grant is scoped to the receiving app.
 */
private fun shareExport(context: Context, exported: ExportedFile) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exported.file)
    }.getOrNull() ?: run {
        Toast.makeText(context, "Couldn't open the export file.", Toast.LENGTH_LONG).show()
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = exported.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, exported.file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share ${exported.file.name}"))
}

/** An export row: icon + label, swapping the chevron for a spinner while the download runs. */
@Composable
private fun ExportRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled || busy) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled || busy) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.weight(1f),
        )
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = SpotterTheme.pulse.effort,
            )
        } else {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The picker values for the training profile, kept **identical to the onboarding questionnaire**
 * (`ui/onboarding/OnboardingScreen.kt`) so a profile edited here stays comparable to one collected
 * there — `UserProfile.toContextString()` humanises these codes for the coach's prompt.
 */
private object TrainingProfileOptions {
    val EXPERIENCE = listOf(
        "BEGINNER" to "Beginner",
        "INTERMEDIATE" to "Intermediate",
        "ADVANCED" to "Advanced",
    )
    val GOAL = listOf(
        "MUSCLE" to "Build muscle",
        "FAT_LOSS" to "Lose fat",
        "STRENGTH" to "Strength",
        "FITNESS" to "General fitness",
    )
    val AGE_GROUP = listOf(
        "13_17" to "13–17",
        "18_24" to "18–24",
        "25_34" to "25–34",
        "35_44" to "35–44",
        "45_54" to "45–54",
        "55_64" to "55–64",
        "65_PLUS" to "65+",
    )
}

/**
 * A labelled single-choice chip group (value → label). Wrapped in fixed-size rows rather than a
 * flow layout so it lays out identically everywhere; tapping the selected chip clears the field
 * (the profile contract reads an empty string as "cleared").
 */
@Composable
private fun ChoiceRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    perRow: Int = 3,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        options.chunked(perRow).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chunk.forEach { (value, text) ->
                    FilterChip(
                        selected = selected == value,
                        onClick = { onSelect(if (selected == value) "" else value) },
                        label = { Text(text, style = MaterialTheme.typography.labelLarge) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Keep the last row's chips the same width as the full rows above it.
                repeat(perRow - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** A label + value row for the About section (e.g. "Server  0.1.0 · a1b2c3d"). */
@Composable
private fun VersionRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Profile card: a gradient initial avatar next to the user's name + email. */
@Composable
private fun ProfileHeader(name: String, email: String) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(SpotterTheme.pulse.effortDim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.trim().take(1).uppercase().ifBlank { "?" },
                    style = MaterialTheme.typography.titleLarge,
                    color = SpotterTheme.pulse.effort,
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

/** A titled settings group rendered on a [PanelCard] with a channel [SectionHeader]. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** A title + subtitle row with a trailing switch (the Reminders-toggle layout, reusable). */
@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A chevron navigation row used inside settings groups. */
@Composable
private fun NavRow(
    label: String,
    onClick: () -> Unit,
    icon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(12.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** A 24h-wrapping hour stepper (0..23), rendered as a 12h clock label like "9 PM". */
@Composable
private fun HourStepper(hour: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange((hour + 23) % 24) }) { Text("−") }
        Text(
            text = formatHour(hour),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(72.dp),
        )
        OutlinedButton(onClick = { onChange((hour + 1) % 24) }) { Text("+") }
    }
}

/** Formats a 0..23 hour as a 12h clock label, e.g. 0 -> "12 AM", 8 -> "8 AM", 21 -> "9 PM". */
private fun formatHour(hour: Int): String {
    val h = ((hour % 24) + 24) % 24
    val suffix = if (h < 12) "AM" else "PM"
    val twelve = when (h % 12) {
        0 -> 12
        else -> h % 12
    }
    return "$twelve $suffix"
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
