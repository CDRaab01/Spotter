package com.spotter.ui.settings

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.export.ExportedFile
import com.spotter.ui.navigation.Screen

/**
 * Settings: navigation, one-shot effects, and the reset dialog. Everything it *renders* lives in
 * [SettingsContent], which is stateless so screenshot tests and the design audit can drive the real
 * screen from a fixture instead of re-implementing a lookalike.
 */
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
    val morningNudge by viewModel.morningNudgeTime.collectAsState()
    val eveningNudge by viewModel.eveningNudgeTime.collectAsState()
    val quietStart by viewModel.quietStartTime.collectAsState()
    val quietEnd by viewModel.quietEndTime.collectAsState()
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

    val state = SettingsUiState(
        user = userState,
        appVersion = viewModel.appVersion,
        serverVersion = serverVersion,
        darkMode = darkMode,
        weightUnit = weightUnit,
        distanceUnit = distanceUnit,
        trackRpe = trackRpe,
        autoStartRest = autoStartRest,
        cadenceDays = cadenceDays,
        nudgeEnabled = nudgeEnabled,
        morningNudge = morningNudge,
        eveningNudge = eveningNudge,
        quietStart = quietStart,
        quietEnd = quietEnd,
        programs = programs,
        exporting = exporting,
        healthAvailability = viewModel.healthConnectAvailability,
        healthConnectEnabled = healthConnectEnabled,
        serverUrl = serverUrl,
        profileDraft = profileDraft,
        profileSaving = profileSaving,
        resetting = resetting,
    )

    val actions = SettingsActions(
        onSetTrackRpe = viewModel::setTrackRpe,
        onSetAutoStartRest = viewModel::setAutoStartRest,
        onSetCadenceDays = viewModel::setWorkoutCadenceDays,
        onSetNudgeEnabled = viewModel::setWorkoutNudgeEnabled,
        onSetMorningNudge = viewModel::setMorningNudgeTime,
        onSetEveningNudge = viewModel::setEveningNudgeTime,
        onSetQuietWindow = viewModel::setQuietWindow,
        onOpenPrograms = { navController.navigate(Screen.Programs.route) },
        onOpenProgram = { id -> navController.navigate(Screen.ProgramDetail.createRoute(id)) },
        onSetProfileEquipment = viewModel::setProfileEquipment,
        onSetProfileExperience = viewModel::setProfileExperience,
        onSetProfileGoal = viewModel::setProfileGoal,
        onSetProfileAgeGroup = viewModel::setProfileAgeGroup,
        onSetProfileLimitations = viewModel::setProfileLimitations,
        onSaveProfile = viewModel::saveProfile,
        onOpenExerciseLibrary = { navController.navigate(Screen.ExerciseLibrary.route) },
        onOpenHistory = { navController.navigate(Screen.SessionHistory.route) },
        onExport = viewModel::export,
        onSetDarkMode = viewModel::setDarkMode,
        onSetWeightUnit = viewModel::setWeightUnit,
        onSetDistanceUnit = viewModel::setDistanceUnit,
        onSetHealthConnectEnabled = viewModel::setHealthConnectEnabled,
        onSaveServerUrl = viewModel::setServerUrl,
        onSignOut = viewModel::logout,
        onResetAccount = { showResetDialog = true },
    )

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
        SettingsContent(
            state = state,
            actions = actions,
            modifier = Modifier.padding(padding),
        )
    }
}

/**
 * Hands a finished export to the Android share sheet via the app's FileProvider — the only way a
 * cache file can leave the app sandbox. The read grant is scoped to the receiving app.
 */
private fun shareExport(context: Context, exported: ExportedFile) {
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exported.file)
    }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = exported.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, exported.file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share ${exported.file.name}"))
    }
}
