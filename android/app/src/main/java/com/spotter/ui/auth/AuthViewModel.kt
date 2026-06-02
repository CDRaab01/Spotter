package com.spotter.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.repository.AuthRepository
import com.spotter.util.AppPreferences
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    /**
     * Whether this device has completed the onboarding questionnaire. After login we route
     * to Home when true, or back through onboarding when false (e.g. after an account reset).
     */
    suspend fun isOnboardingDone(): Boolean = appPreferences.onboardingDone.first()

    private val _authState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val authState: StateFlow<UiState<Unit>> = _authState

    fun login(email: String, password: String) {
        viewModelScope.launch {
            _authState.value = UiState.Loading
            _authState.value = try {
                authRepository.login(email, password)
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

    fun clearState() {
        _authState.value = UiState.Idle
    }
}
