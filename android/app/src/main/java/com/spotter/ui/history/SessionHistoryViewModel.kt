package com.spotter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.SessionSummary
import com.spotter.data.repository.SessionRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionHistoryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _sessions = MutableStateFlow<UiState<List<SessionSummary>>>(UiState.Loading)
    val sessions: StateFlow<UiState<List<SessionSummary>>> = _sessions.asStateFlow()

    init {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            _sessions.value = UiState.Loading
            try {
                val result = sessionRepository.listSessions()
                _sessions.value = UiState.Success(result)
            } catch (e: Exception) {
                _sessions.value = UiState.Error(e.message ?: "Failed to load sessions")
            }
        }
    }

    fun deleteSession(sessionId: String) {
        // Optimistically drop it from the visible list, then reconcile with the server.
        val current = _sessions.value
        if (current is UiState.Success) {
            _sessions.value = UiState.Success(current.data.filterNot { it.id == sessionId })
        }
        viewModelScope.launch {
            try {
                sessionRepository.deleteSession(sessionId)
            } catch (_: Exception) {
                // Re-sync on failure so the list reflects the true server state.
                loadSessions()
            }
        }
    }
}
