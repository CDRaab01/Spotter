package com.spotter.ui.auth

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.remote.SuiteAuthManager
import com.spotter.data.repository.AuthRepository
import com.spotter.util.AppPreferences
import com.spotter.util.AuthEventBus
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val appPreferences: AppPreferences,
    private val suiteAuthManager: SuiteAuthManager,
    authEventBus: AuthEventBus,
) : ViewModel() {

    val logoutEvents = authEventBus.events

    /**
     * Whether this device has completed the onboarding questionnaire. After login we route
     * to Home when true, or back through onboarding when false (e.g. after an account reset).
     */
    suspend fun isOnboardingDone(): Boolean = appPreferences.onboardingDone.first()

    private val _authState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val authState: StateFlow<UiState<Unit>> = _authState

    /**
     * The configured server URL, surfaced on the login screen so it can be viewed/overridden before
     * sign-in (the in-app Settings screen is only reachable once authenticated). Sourced from
     * [AppPreferences.serverUrl], which falls back to the build-time `BuildConfig.SERVER_URL`.
     */
    val serverUrl: StateFlow<String> = appPreferences.serverUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /**
     * Normalizes and persists a new server URL from the login screen. Returns true if accepted.
     * Mirrors the normalization in [com.spotter.ui.settings.SettingsViewModel], but additionally
     * defaults a missing scheme to https so the user can type a bare host. No token clearing is
     * needed here since the user isn't signed in yet.
     */
    fun setServerUrl(value: String): Boolean {
        val normalized = normalizeServerUrl(value) ?: return false
        viewModelScope.launch { appPreferences.setServerUrl(normalized) }
        return true
    }

    private fun normalizeServerUrl(value: String): String? {
        var trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://$trimmed"
        }
        val withSlash = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val parsed = withSlash.toHttpUrlOrNull() ?: return null
        return if (parsed.scheme == "http" || parsed.scheme == "https") withSlash else null
    }

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                authRepository.login(email, password)
                // Existing account → not a new user; skip the onboarding intro on this + future launches.
                appPreferences.setOnboardingDone()
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Login failed")
            }
        }
    }

    fun register(name: String, email: String, password: String, inviteCode: String? = null) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                authRepository.register(name, email, password, inviteCode)
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Registration failed")
            }
        }
    }

    fun forgotPassword(email: String) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                authRepository.forgotPassword(email)
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Request failed. Please try again.")
            }
        }
    }

    fun resetPassword(token: String, newPassword: String) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                authRepository.resetPassword(token, newPassword)
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Invalid or expired code. Please try again.")
            }
        }
    }

    /** Intent that launches the Dragonfly (suite SSO) sign-in — launch via an ActivityResult. */
    fun suiteAuthorizeIntent(): Intent = suiteAuthManager.authorizeIntent()

    /** Handle the sign-in result: exchange → /auth/suite → session. Success drives navigation. */
    fun completeSuiteLogin(data: Intent?) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                suiteAuthManager.complete(data)
                // Existing account → not a new user; skip the onboarding intro on this + future launches.
                appPreferences.setOnboardingDone()
                UiState.Success(Unit)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Dragonfly sign-in failed")
            }
        }
    }

    fun clearState() {
        _authState.value = UiState.Idle
    }
}
