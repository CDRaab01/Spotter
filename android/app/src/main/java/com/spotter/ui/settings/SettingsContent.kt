package com.spotter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spotter.data.export.ExportKind
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.model.UserOut
import com.spotter.data.model.VersionOut
import com.spotter.health.HealthConnectManager
import com.spotter.ui.components.PulsingDots
import com.spotter.ui.theme.SpotterTheme
import com.spotter.util.AppPreferences
import com.spotter.util.DarkModePreference
import com.spotter.util.DistanceUnit
import com.spotter.util.TimeOfDay
import com.spotter.util.UiState
import com.spotter.util.UserProfile
import com.spotter.util.WeightUnit
import design.pulse.ui.components.Caption
import design.pulse.ui.components.ProfileHeader
import design.pulse.ui.components.PulseSegmentedControl
import design.pulse.ui.components.PulseSettingRow
import design.pulse.ui.components.PulseStepperRow
import design.pulse.ui.components.PulseSwitchRow
import design.pulse.ui.components.PulseTimeRow
import design.pulse.ui.components.SettingsSection

/**
 * Everything the Settings screen renders, as plain data. Every field is defaulted so a screenshot
 * or preview fixture only has to name what it's actually exercising.
 */
data class SettingsUiState(
    val user: UiState<UserOut> = UiState.Loading,
    val appVersion: String = "",
    val serverVersion: UiState<VersionOut> = UiState.Loading,
    val darkMode: DarkModePreference = DarkModePreference.SYSTEM,
    val weightUnit: WeightUnit = WeightUnit.LBS,
    val distanceUnit: DistanceUnit = DistanceUnit.MI,
    val trackRpe: Boolean = false,
    val autoStartRest: Boolean = true,
    val cadenceDays: Int = AppPreferences.DEFAULT_CADENCE_DAYS,
    val nudgeEnabled: Boolean = false,
    val morningNudge: TimeOfDay = TimeOfDay(AppPreferences.NUDGE_HOUR, 0),
    val eveningNudge: TimeOfDay = TimeOfDay(AppPreferences.EVENING_NUDGE_HOUR, 0),
    val quietStart: TimeOfDay = TimeOfDay(AppPreferences.DEFAULT_QUIET_START_HOUR, 0),
    val quietEnd: TimeOfDay = TimeOfDay(AppPreferences.DEFAULT_QUIET_END_HOUR, 0),
    val programs: List<WorkoutProgramEntity> = emptyList(),
    val exporting: ExportKind? = null,
    val healthAvailability: HealthConnectManager.Availability =
        HealthConnectManager.Availability.AVAILABLE,
    val healthConnectEnabled: Boolean = false,
    val serverUrl: String = "",
    val profileDraft: UserProfile = UserProfile(),
    val profileSaving: Boolean = false,
    val resetting: Boolean = false,
)

/**
 * Everything the Settings screen can do. Defaulted to no-ops so fixtures can render the screen
 * without wiring a ViewModel or a NavController.
 */
data class SettingsActions(
    val onSetTrackRpe: (Boolean) -> Unit = {},
    val onSetAutoStartRest: (Boolean) -> Unit = {},
    val onSetCadenceDays: (Int) -> Unit = {},
    val onSetNudgeEnabled: (Boolean) -> Unit = {},
    val onSetMorningNudge: (TimeOfDay) -> Unit = {},
    val onSetEveningNudge: (TimeOfDay) -> Unit = {},
    val onSetQuietWindow: (TimeOfDay, TimeOfDay) -> Unit = { _, _ -> },
    val onOpenPrograms: () -> Unit = {},
    val onOpenProgram: (String) -> Unit = {},
    val onSetProfileEquipment: (String) -> Unit = {},
    val onSetProfileExperience: (String) -> Unit = {},
    val onSetProfileGoal: (String) -> Unit = {},
    val onSetProfileAgeGroup: (String) -> Unit = {},
    val onSetProfileLimitations: (String) -> Unit = {},
    val onSaveProfile: () -> Unit = {},
    val onOpenExerciseLibrary: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    val onExport: (ExportKind) -> Unit = {},
    val onSetDarkMode: (DarkModePreference) -> Unit = {},
    val onSetWeightUnit: (WeightUnit) -> Unit = {},
    val onSetDistanceUnit: (DistanceUnit) -> Unit = {},
    val onSetHealthConnectEnabled: (Boolean) -> Unit = {},
    val onSaveServerUrl: (String) -> Unit = {},
    val onSignOut: () -> Unit = {},
    val onResetAccount: () -> Unit = {},
)

/**
 * The Settings screen body — stateless, so it renders identically from a ViewModel or a test
 * fixture. Grouped so the tap-only rows come first and the one long form sits below them; the
 * screen used to lead with the training-profile form, which pushed every other section off-screen.
 */
@Composable
internal fun SettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ProfileBlock(state.user)
        WorkoutBlock(state, actions)
        RemindersBlock(state, actions)
        ProgramsBlock(state, actions)
        TrainingProfileBlock(state, actions)
        LibraryAndDataBlock(state, actions)
        AppearanceBlock(state, actions)
        ConnectionsBlock(state, actions)
        AccountBlock(state, actions)
        AboutBlock(state)
    }
}

@Composable
internal fun ProfileBlock(user: UiState<UserOut>) {
    when (user) {
        is UiState.Loading -> Box(
            Modifier.fillMaxWidth().padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) { PulsingDots() }

        is UiState.Error -> Text(user.message, color = MaterialTheme.colorScheme.error)

        is UiState.Success -> ProfileHeader(name = user.data.name, email = user.data.email)

        else -> Unit
    }
}

@Composable
internal fun WorkoutBlock(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("Workout") {
        PulseSwitchRow(
            title = "Track RPE",
            subtitle = "Completed sets show a 1–10 effort entry (one decimal).",
            checked = state.trackRpe,
            onCheckedChange = actions.onSetTrackRpe,
        )
        PulseSwitchRow(
            title = "Auto-start rest timer",
            subtitle = "Completing a set starts the rest countdown. Off = a Start rest button " +
                "appears instead.",
            checked = state.autoStartRest,
            onCheckedChange = actions.onSetAutoStartRest,
        )
        PulseStepperRow(
            label = "Train every",
            value = state.cadenceDays,
            onValueChange = actions.onSetCadenceDays,
            min = 1,
            max = 14,
            valueLabel = { "$it ${if (it == 1) "day" else "days"}" },
        )
        Caption("How often upcoming workouts are spaced on Home and the calendar.")
    }
}

@Composable
internal fun RemindersBlock(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("Reminders") {
        PulseSwitchRow(
            title = "Workout nudges",
            // Deliberately no times in this copy: they're user-set now and each has its own row
            // below, so naming them here could only ever drift out of date.
            subtitle = "A reminder on workout days, a streak-saver if the day is slipping, and a " +
                "one-off comeback nudge after a few missed days. Never on rest days.",
            checked = state.nudgeEnabled,
            onCheckedChange = actions.onSetNudgeEnabled,
        )
        if (state.nudgeEnabled) {
            PulseTimeRow(
                label = "Morning reminder",
                hour = state.morningNudge.hour,
                minute = state.morningNudge.minute,
                onTimeChange = { h, m -> actions.onSetMorningNudge(TimeOfDay(h, m)) },
            )
            PulseTimeRow(
                label = "Evening streak-saver",
                hour = state.eveningNudge.hour,
                minute = state.eveningNudge.minute,
                onTimeChange = { h, m -> actions.onSetEveningNudge(TimeOfDay(h, m)) },
            )
            Spacer(Modifier.height(8.dp))
            Caption("Quiet hours")
            PulseTimeRow(
                label = "From",
                hour = state.quietStart.hour,
                minute = state.quietStart.minute,
                onTimeChange = { h, m -> actions.onSetQuietWindow(TimeOfDay(h, m), state.quietEnd) },
            )
            PulseTimeRow(
                label = "To",
                hour = state.quietEnd.hour,
                minute = state.quietEnd.minute,
                onTimeChange = { h, m -> actions.onSetQuietWindow(state.quietStart, TimeOfDay(h, m)) },
            )
            Text(
                "A nudge that would land inside this window is skipped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ProgramsBlock(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("Programs") {
        if (state.programs.isEmpty()) {
            Text(
                "No programs yet. Ask the AI coach for a multi-day program, or start from a preset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PulseSettingRow("Browse programs & presets", onClick = actions.onOpenPrograms)
        } else {
            state.programs.forEach { program ->
                PulseSettingRow(
                    label = program.name,
                    onClick = { actions.onOpenProgram(program.id) },
                    trailing = if (program.isActive) {
                        {
                            Text(
                                "Active",
                                style = MaterialTheme.typography.labelSmall,
                                color = SpotterTheme.pulse.effort,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@Composable
internal fun TrainingProfileBlock(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("Training profile") {
        Text(
            "Your coach reads this before every reply — keep the equipment line current and it " +
                "won't have to ask again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // Equipment leads: it's the thing that actually changes (new gym, new dumbbells) and the
        // thing the coach was forgetting.
        OutlinedTextField(
            value = state.profileDraft.equipment,
            onValueChange = actions.onSetProfileEquipment,
            label = { Text("Equipment") },
            supportingText = { Text("e.g. dumbbells up to 50lb, pull-up bar, bands") },
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        ChipGroup(
            label = "Experience",
            options = TrainingProfileOptions.EXPERIENCE,
            selected = state.profileDraft.experience,
            onSelect = actions.onSetProfileExperience,
        )

        Spacer(Modifier.height(12.dp))
        ChipGroup(
            label = "Goal",
            options = TrainingProfileOptions.GOAL,
            selected = state.profileDraft.goal,
            onSelect = actions.onSetProfileGoal,
        )

        Spacer(Modifier.height(12.dp))
        ChipGroup(
            label = "Age group",
            options = TrainingProfileOptions.AGE_GROUP,
            selected = state.profileDraft.ageGroup,
            onSelect = actions.onSetProfileAgeGroup,
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.profileDraft.limitations,
            onValueChange = actions.onSetProfileLimitations,
            label = { Text("Limitations / injuries") },
            supportingText = { Text("e.g. lower back, left shoulder — leave blank if none") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = actions.onSaveProfile,
            enabled = !state.profileSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.profileSaving) "Saving…" else "Save training profile")
        }
    }
}

@Composable
internal fun LibraryAndDataBlock(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("Library & data") {
        PulseSettingRow(
            label = "Exercise library",
            leading = {
                Icon(
                    Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = actions.onOpenExerciseLibrary,
        )
        PulseSettingRow(
            label = "Workout history",
            leading = {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = actions.onOpenHistory,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Download your own copy. Files are handed straight to the share sheet — save them, " +
                "mail them, drop them in a spreadsheet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExportRow(
            icon = Icons.Default.Description,
            label = "Workout data (JSON)",
            kind = ExportKind.JSON,
            state = state,
            onExport = actions.onExport,
        )
        ExportRow(
            icon = Icons.Default.TableChart,
            label = "Sets (CSV)",
            kind = ExportKind.CSV,
            state = state,
            onExport = actions.onExport,
        )
    }
}

@Composable
private fun ExportRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    kind: ExportKind,
    state: SettingsUiState,
    onExport: (ExportKind) -> Unit,
) {
    val busy = state.exporting == kind
    PulseSettingRow(
        label = label,
        leading = {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailing = if (busy) {
            { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) }
        } else {
            null
        },
        chevron = !busy,
        onClick = { onExport(kind) },
        enabled = state.exporting == null,
    )
}

@Composable
internal fun AppearanceBlock(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("Appearance & units") {
        Caption("Theme")
        Spacer(Modifier.height(4.dp))
        PulseSegmentedControl(
            options = listOf("System", "Light", "Dark"),
            selectedIndex = DarkModePreference.entries.indexOf(state.darkMode),
            onSelect = { actions.onSetDarkMode(DarkModePreference.entries[it]) },
        )
        Spacer(Modifier.height(12.dp))
        Caption("Weight")
        Spacer(Modifier.height(4.dp))
        PulseSegmentedControl(
            options = WeightUnit.entries.map { if (it == WeightUnit.KG) "kg" else "lbs" },
            selectedIndex = WeightUnit.entries.indexOf(state.weightUnit),
            onSelect = { actions.onSetWeightUnit(WeightUnit.entries[it]) },
        )
        Spacer(Modifier.height(12.dp))
        Caption("Distance")
        Spacer(Modifier.height(4.dp))
        PulseSegmentedControl(
            options = DistanceUnit.entries.map { if (it == DistanceUnit.KM) "km" else "mi" },
            selectedIndex = DistanceUnit.entries.indexOf(state.distanceUnit),
            onSelect = { actions.onSetDistanceUnit(DistanceUnit.entries[it]) },
        )
    }
}

@Composable
internal fun ConnectionsBlock(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("Connections") {
        val available = state.healthAvailability == HealthConnectManager.Availability.AVAILABLE
        PulseSwitchRow(
            title = "Sync to Health Connect",
            subtitle = when (state.healthAvailability) {
                HealthConnectManager.Availability.AVAILABLE ->
                    "Finished workouts and weigh-ins are copied to Health Connect. Spotter only " +
                        "writes — it never reads your health data."
                HealthConnectManager.Availability.UPDATE_REQUIRED ->
                    "Update Health Connect on this device to turn this on."
                HealthConnectManager.Availability.UNAVAILABLE ->
                    "Health Connect isn't available on this device."
            },
            checked = state.healthConnectEnabled && available,
            enabled = available,
            onCheckedChange = actions.onSetHealthConnectEnabled,
        )
        Spacer(Modifier.height(12.dp))
        var serverUrlInput by remember(state.serverUrl) { mutableStateOf(state.serverUrl) }
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
            onClick = { actions.onSaveServerUrl(serverUrlInput) },
            enabled = serverUrlInput.isNotBlank() && serverUrlInput != state.serverUrl,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save server URL")
        }
    }
}

@Composable
internal fun AccountBlock(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection("Account") {
        Button(
            onClick = actions.onSignOut,
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
            onClick = actions.onResetAccount,
            enabled = !state.resetting,
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

@Composable
internal fun AboutBlock(state: SettingsUiState) {
    // Keeps its own section deliberately: CLAUDE.md and deploy/README.md both name
    // "Settings → About" as the deploy-verification step.
    SettingsSection("About") {
        PulseSettingRow(label = "App", value = state.appVersion)
        val serverValue = when (val v = state.serverVersion) {
            is UiState.Success -> {
                val info = v.data
                if (info.commit.isBlank() || info.commit == "unknown") info.version
                else "${info.version} · ${info.commit}"
            }
            is UiState.Error -> "Unavailable"
            else -> "Checking…"
        }
        PulseSettingRow(label = "Server", value = serverValue)
        (state.serverVersion as? UiState.Success)?.data?.builtAt
            ?.takeIf { it.isNotBlank() && it != "unknown" }
            ?.let { builtAt ->
                Text(
                    "Deployed $builtAt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
    }
}

/**
 * A labelled single-choice chip group. Chips size to their own content and wrap naturally —
 * an earlier version split them into fixed-count rows of equal width, which squeezed longer
 * labels until they broke *inside* the word ("Intermediat/e"). Tapping the selected chip clears
 * the field (the profile contract reads an empty string as "cleared").
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipGroup(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(if (selected == value) "" else value) },
                    label = { Text(text, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
    }
}

/**
 * The picker values for the training profile, kept **identical to the onboarding questionnaire**
 * (`ui/onboarding/OnboardingScreen.kt`) so a profile edited here stays comparable to one collected
 * there — `UserProfile.toContextString()` humanises these codes for the coach's prompt.
 */
internal object TrainingProfileOptions {
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
