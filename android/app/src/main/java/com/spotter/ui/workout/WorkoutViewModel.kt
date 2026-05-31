package com.spotter.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SetLogCreate
import com.spotter.data.repository.SessionRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _session = MutableStateFlow<UiState<SessionOut>>(UiState.Loading)
    val session: StateFlow<UiState<SessionOut>> = _session

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            _session.value = UiState.Loading
            _session.value = try {
                UiState.Success(sessionRepository.getSession(sessionId))
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to load session")
            }
        }
    }

    fun logSet(sessionId: String, req: SetLogCreate) {
        viewModelScope.launch {
            try {
                sessionRepository.logSet(sessionId, req)
                loadSession(sessionId)
            } catch (_: Exception) {}
        }
    }
}
