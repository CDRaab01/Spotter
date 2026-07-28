package com.spotter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.BuildConfig
import com.spotter.data.local.SpotterDatabase
import com.spotter.data.export.ExportKind
import com.spotter.data.export.ExportRepository
import com.spotter.data.export.ExportedFile
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.model.UserOut
import com.spotter.data.model.VersionOut
import com.spotter.data.remote.ApiService
import com.spotter.data.repository.ProfileRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.di.IoDispatcher
import com.spotter.health.HealthConnectManager
import com.spotter.util.AppPreferences
import com.spotter.util.DarkModePreference
import com.spotter.util.DistanceUnit
import com.spotter.util.TokenStore
import com.spotter.util.UiState
import com.spotter.util.UserProfile
import com.spotter.util.WeightUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
    private val appPreferences: AppPreferences,
    private val database: SpotterDatabase,
    private val programRepository: ProgramRepository,
    private val profileRepository: ProfileRepository,
    private val exportRepository: ExportRepository,
    private val healthConnectManager: HealthConnectManager,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _user = MutableStateFlow<UiState<UserOut>>(UiState.Loading)
    val user: StateFlow<UiState<UserOut>> = _user.asStateFlow()

    /** This app build's version, e.g. "1.0 (1)". Static — read from BuildConfig. */
    val appVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /** The connected server's build info — confirms a redeploy landed. */
    private val _serverVersion = MutableStateFlow<UiState<VersionOut>>(UiState.Loading)
    val serverVersion: StateFlow<UiState<VersionOut>> = _serverVersion.asStateFlow()

    /** All programs (incl. AI-generated), surfaced for the Programs settings section. */
    val programs: StateFlow<List<WorkoutProgramEntity>> = programRepository.programs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _navigateToLogin = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToLogin: SharedFlow<Unit> = _navigateToLogin.asSharedFlow()

    val darkMode: StateFlow<DarkModePreference> = appPreferences.darkMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DarkModePreference.SYSTEM)

    val weightUnit: StateFlow<WeightUnit> = appPreferences.weightUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUnit.LBS)

    val distanceUnit: StateFlow<DistanceUnit> = appPreferences.distanceUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DistanceUnit.MI)

    /** Workout-mode toggles: RPE tracking (opt-in) + automatic rest start (default on). */
    val trackRpe: StateFlow<Boolean> = appPreferences.trackRpe
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val autoStartRest: StateFlow<Boolean> = appPreferences.autoStartRest
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val workoutCadenceDays: StateFlow<Int> = appPreferences.workoutCadenceDays
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_CADENCE_DAYS,
        )

    val serverUrl: StateFlow<String> = appPreferences.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** Opt-in workout-morning nudge toggle + quiet-hours window (Settings → Reminders). */
    val workoutNudgeEnabled: StateFlow<Boolean> = appPreferences.workoutNudgeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val quietStartHour: StateFlow<Int> = appPreferences.quietStartHour
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_QUIET_START_HOUR,
        )

    val quietEndHour: StateFlow<Int> = appPreferences.quietEndHour
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            AppPreferences.DEFAULT_QUIET_END_HOUR,
        )

    private val _serverUrlMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val serverUrlMessage: SharedFlow<String> = _serverUrlMessage.asSharedFlow()

    private val _resetting = MutableStateFlow(false)
    val resetting: StateFlow<Boolean> = _resetting.asStateFlow()

    private val _resetError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val resetError: SharedFlow<String> = _resetError.asSharedFlow()

    private val _navigateToOnboarding = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToOnboarding: SharedFlow<Unit> = _navigateToOnboarding.asSharedFlow()

    // ── Export data ───────────────────────────────────────────────────────────

    /** The export currently downloading, or null when idle — drives the per-row spinner. */
    private val _exporting = MutableStateFlow<ExportKind?>(null)
    val exporting: StateFlow<ExportKind?> = _exporting.asStateFlow()

    /** A finished download, ready for the screen to hand to the Android share sheet. */
    private val _exportReady = MutableSharedFlow<ExportedFile>(extraBufferCapacity = 1)
    val exportReady: SharedFlow<ExportedFile> = _exportReady.asSharedFlow()

    private val _exportError = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val exportError: SharedFlow<String> = _exportError.asSharedFlow()

    // ── Training profile ──────────────────────────────────────────────────────

    /**
     * The editable training profile (equipment, experience, goal, age group, limitations) — the
     * context the AI coach is given. Before this section existed it could only ever be written once,
     * by the onboarding questionnaire, which most users never see (login marks onboarding done), so
     * the coach kept asking what equipment the user had.
     */
    private val _profileDraft = MutableStateFlow(UserProfile())
    val profileDraft: StateFlow<UserProfile> = _profileDraft.asStateFlow()

    private val _profileSaving = MutableStateFlow(false)
    val profileSaving: StateFlow<Boolean> = _profileSaving.asStateFlow()

    private val _profileMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val profileMessage: SharedFlow<String> = _profileMessage.asSharedFlow()

    /** Set once the user edits a field, so a slow refresh can't overwrite what they're typing. */
    private var profileEdited = false

    // ── Health Connect ────────────────────────────────────────────────────────

    /** Whether this device can use Health Connect at all — static per install, read once. */
    val healthConnectAvailability: HealthConnectManager.Availability =
        healthConnectManager.availability()

    val healthConnectEnabled: StateFlow<Boolean> = appPreferences.healthConnectEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Emitted when the user turns the toggle on and the write permissions aren't granted yet: the
     * screen launches [HealthConnectManager.permissionContract] with this set, then reports back
     * through [onHealthPermissionsResult]. (The permission request needs an Activity, so it can't
     * live here.)
     */
    private val _requestHealthPermissions = MutableSharedFlow<Set<String>>(extraBufferCapacity = 1)
    val requestHealthPermissions: SharedFlow<Set<String>> = _requestHealthPermissions.asSharedFlow()

    private val _healthMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val healthMessage: SharedFlow<String> = _healthMessage.asSharedFlow()

    init {
        loadUser()
        loadServerVersion()
        loadProfile()
        viewModelScope.launch { runCatching { programRepository.sync() } }
    }

    /**
     * Fills the form from the local mirror immediately (so it is never blank while a pull runs),
     * then refreshes from the server and re-reads. Offline the refresh is a silent no-op and the
     * mirror stands.
     */
    private fun loadProfile() {
        viewModelScope.launch {
            if (!profileEdited) _profileDraft.value = profileRepository.current()
            runCatching { profileRepository.refresh() }
            if (!profileEdited) _profileDraft.value = profileRepository.current()
        }
    }

    fun setProfileEquipment(value: String) = updateProfileDraft { it.copy(equipment = value) }
    fun setProfileExperience(value: String) = updateProfileDraft { it.copy(experience = value) }
    fun setProfileGoal(value: String) = updateProfileDraft { it.copy(goal = value) }
    fun setProfileAgeGroup(value: String) = updateProfileDraft { it.copy(ageGroup = value) }
    fun setProfileLimitations(value: String) = updateProfileDraft { it.copy(limitations = value) }

    private fun updateProfileDraft(transform: (UserProfile) -> UserProfile) {
        profileEdited = true
        _profileDraft.value = transform(_profileDraft.value)
    }

    /**
     * Saves the profile through [ProfileRepository]: the local mirror first (so the coach sees it
     * on the very next message either way), then the server. An offline save is queued and says so;
     * an HTTP failure is reported rather than silently claiming success.
     */
    fun saveProfile() {
        if (_profileSaving.value) return
        viewModelScope.launch {
            _profileSaving.value = true
            try {
                val pushed = profileRepository.save(_profileDraft.value)
                profileEdited = false
                _profileMessage.emit(
                    if (pushed) "Training profile saved"
                    else "Saved on this device — it'll sync when you're back online.",
                )
            } catch (_: Exception) {
                _profileMessage.emit("Couldn't save your profile. Try again.")
            } finally {
                _profileSaving.value = false
            }
        }
    }

    private fun loadUser() {
        viewModelScope.launch {
            _user.value = UiState.Loading
            try {
                val result = api.getMe()
                _user.value = UiState.Success(result)
            } catch (e: Exception) {
                _user.value = UiState.Error(e.message ?: "Failed to load user")
            }
        }
    }

    fun loadServerVersion() {
        viewModelScope.launch {
            _serverVersion.value = UiState.Loading
            try {
                _serverVersion.value = UiState.Success(api.getServerVersion())
            } catch (e: Exception) {
                _serverVersion.value = UiState.Error(e.message ?: "Couldn't reach server")
            }
        }
    }

    fun setDarkMode(value: DarkModePreference) {
        viewModelScope.launch { appPreferences.setDarkMode(value) }
    }

    fun setWeightUnit(value: WeightUnit) {
        viewModelScope.launch { appPreferences.setWeightUnit(value) }
    }

    fun setDistanceUnit(value: DistanceUnit) {
        viewModelScope.launch { appPreferences.setDistanceUnit(value) }
    }

    fun setWorkoutCadenceDays(value: Int) {
        viewModelScope.launch { appPreferences.setWorkoutCadenceDays(value) }
    }

    fun setTrackRpe(value: Boolean) {
        viewModelScope.launch { appPreferences.setTrackRpe(value) }
    }

    fun setAutoStartRest(value: Boolean) {
        viewModelScope.launch { appPreferences.setAutoStartRest(value) }
    }

    /**
     * Toggles the workout-morning nudge. The actual WorkManager (re)schedule is driven by
     * [com.spotter.SpotterApp], which observes this preference — so flipping it here is enough.
     */
    fun setWorkoutNudgeEnabled(value: Boolean) {
        viewModelScope.launch { appPreferences.setWorkoutNudgeEnabled(value) }
    }

    fun setQuietHours(startHour: Int, endHour: Int) {
        viewModelScope.launch { appPreferences.setQuietHours(startHour, endHour) }
    }

    /**
     * Downloads an export into app cache and emits it on [exportReady] for the share sheet. One at
     * a time; a failure (offline, HTTP error) surfaces on [exportError] and leaves nothing behind.
     */
    fun export(kind: ExportKind) {
        if (_exporting.value != null) return
        viewModelScope.launch {
            _exporting.value = kind
            try {
                _exportReady.emit(
                    when (kind) {
                        ExportKind.JSON -> exportRepository.exportJson()
                        ExportKind.CSV -> exportRepository.exportCsv()
                    }
                )
            } catch (_: Exception) {
                _exportError.emit("Couldn't export — check your connection.")
            } finally {
                _exporting.value = null
            }
        }
    }

    /**
     * Flips the Health Connect mirror. Turning it ON only sticks once the write permissions are
     * granted — otherwise the toggle stays off and [requestHealthPermissions] asks for them, with
     * [onHealthPermissionsResult] completing the flip. Turning it OFF is unconditional.
     */
    fun setHealthConnectEnabled(value: Boolean) {
        viewModelScope.launch {
            if (!value) {
                appPreferences.setHealthConnectEnabled(false)
                return@launch
            }
            if (healthConnectAvailability != HealthConnectManager.Availability.AVAILABLE) {
                _healthMessage.emit("Health Connect isn't available on this device.")
                return@launch
            }
            if (healthConnectManager.hasPermissions()) {
                appPreferences.setHealthConnectEnabled(true)
            } else {
                _requestHealthPermissions.emit(healthConnectManager.permissions)
            }
        }
    }

    /** The contract the screen registers to ask Health Connect for [HealthConnectManager.permissions]. */
    fun healthPermissionContract() = healthConnectManager.permissionContract()

    /** Result of the Health Connect permission request launched from the screen. */
    fun onHealthPermissionsResult(granted: Set<String>) {
        viewModelScope.launch {
            if (granted.containsAll(healthConnectManager.permissions)) {
                appPreferences.setHealthConnectEnabled(true)
            } else {
                appPreferences.setHealthConnectEnabled(false)
                _healthMessage.emit("Health Connect needs both write permissions to sync.")
            }
        }
    }

    /**
     * Validates and persists a new server URL. On a host change, clears the saved tokens and
     * routes to login, since access/refresh tokens are issued per-server.
     */
    fun setServerUrl(value: String) {
        viewModelScope.launch {
            val normalized = normalizeServerUrl(value)
            if (normalized == null) {
                _serverUrlMessage.emit("Enter a valid URL, e.g. http://100.x.y.z:8000/")
                return@launch
            }
            val previous = appPreferences.serverUrl.firstOrNull()
            val hostChanged = normalized.toHttpUrlOrNull()?.host !=
                previous?.toHttpUrlOrNull()?.host
            appPreferences.setServerUrl(normalized)
            if (hostChanged) {
                tokenStore.clear()
                _navigateToLogin.emit(Unit)
            } else {
                _serverUrlMessage.emit("Server URL saved")
            }
        }
    }

    private fun normalizeServerUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val parsed = withSlash.toHttpUrlOrNull() ?: return null
        return if (parsed.scheme == "http" || parsed.scheme == "https") withSlash else null
    }

    fun logout() {
        viewModelScope.launch {
            tokenStore.clear()
            _navigateToLogin.emit(Unit)
        }
    }

    /**
     * Resets the account: wipes all data on the server (the login is kept), clears the local
     * cache, chat history, and saved questionnaire profile, then routes straight into the
     * onboarding questionnaire while still signed in.
     *
     * Deliberately NOT a sign-out: login unconditionally marks onboarding done (a returning
     * user on a fresh install must skip the intro), so a reset that bounced through the login
     * screen would silently skip the questionnaire it just promised — and the first-run
     * auto-generate would then build a program from an empty profile.
     *
     * The server call happens first (while still authenticated); local state is only cleared
     * if it succeeds, so a failed reset leaves the user signed in and able to retry.
     */
    fun resetAccount() {
        if (_resetting.value) return
        viewModelScope.launch {
            _resetting.value = true
            try {
                api.resetAccount()
                withContext(ioDispatcher) { database.clearAllTables() }
                appPreferences.clearOnboarding()
                _navigateToOnboarding.emit(Unit)
            } catch (e: Exception) {
                _resetError.emit(e.message ?: "Couldn't reset your account. Try again.")
            } finally {
                _resetting.value = false
            }
        }
    }
}
