package com.spotter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.UserOut
import com.spotter.data.remote.ApiService
import com.spotter.util.AppPreferences
import com.spotter.util.DarkModePreference
import com.spotter.util.DistanceUnit
import com.spotter.util.TokenStore
import com.spotter.util.UiState
import com.spotter.util.WeightUnit
import dagger.hilt.android.lifecycle.HiltViewModel
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _user = MutableStateFlow<UiState<UserOut>>(UiState.Loading)
    val user: StateFlow<UiState<UserOut>> = _user.asStateFlow()

    private val _navigateToLogin = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToLogin: SharedFlow<Unit> = _navigateToLogin.asSharedFlow()

    val darkMode: StateFlow<DarkModePreference> = appPreferences.darkMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DarkModePreference.SYSTEM)

    val weightUnit: StateFlow<WeightUnit> = appPreferences.weightUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), WeightUnit.LBS)

    val distanceUnit: StateFlow<DistanceUnit> = appPreferences.distanceUnit
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DistanceUnit.MI)

    val serverUrl: StateFlow<String> = appPreferences.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _serverUrlMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val serverUrlMessage: SharedFlow<String> = _serverUrlMessage.asSharedFlow()

    init {
        loadUser()
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

    fun setDarkMode(value: DarkModePreference) {
        viewModelScope.launch { appPreferences.setDarkMode(value) }
    }

    fun setWeightUnit(value: WeightUnit) {
        viewModelScope.launch { appPreferences.setWeightUnit(value) }
    }

    fun setDistanceUnit(value: DistanceUnit) {
        viewModelScope.launch { appPreferences.setDistanceUnit(value) }
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
}
