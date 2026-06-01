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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    fun logout() {
        viewModelScope.launch {
            tokenStore.clear()
            _navigateToLogin.emit(Unit)
        }
    }
}
