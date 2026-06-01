package com.spotter.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.UserOut
import com.spotter.data.remote.ApiService
import com.spotter.util.TokenStore
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: ApiService,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _user = MutableStateFlow<UiState<UserOut>>(UiState.Loading)
    val user: StateFlow<UiState<UserOut>> = _user.asStateFlow()

    private val _navigateToLogin = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateToLogin: SharedFlow<Unit> = _navigateToLogin.asSharedFlow()

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

    fun logout() {
        viewModelScope.launch {
            tokenStore.clear()
            _navigateToLogin.emit(Unit)
        }
    }
}
