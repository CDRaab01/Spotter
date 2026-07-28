package com.spotter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.BuildConfig
import com.spotter.data.local.SpotterDatabase
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.model.UserOut
import com.spotter.data.model.VersionOut
import com.spotter.data.remote.ApiService
import com.spotter.data.repository.ProgramRepository
import com.spotter.di.IoDispatcher
import com.spotter.util.AppPreferences
import com.spotter.util.DarkModePreference
import com.spotter.util.DistanceUnit
import com.spotter.util.TokenStore
import com.spotter.util.UiState
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

    init {
        loadUser()
        loadServerVersion()
        viewModelScope.launch { runCatching { programRepository.sync() } }
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
