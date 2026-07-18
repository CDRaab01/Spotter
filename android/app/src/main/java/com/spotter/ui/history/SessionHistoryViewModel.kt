package com.spotter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.SessionSummary
import com.spotter.data.repository.SessionRepository
import com.spotter.util.AppPreferences
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionHistoryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val _sessions = MutableStateFlow<UiState<List<SessionSummary>>>(UiState.Loading)
    val sessions: StateFlow<UiState<List<SessionSummary>>> = _sessions.asStateFlow()

    /**
     * Non-null when the list came from the Room mirror because the server was unreachable
     * (connectivity, not an HTTP error): the timestamp of the last successful sync, rendered by
     * the stale banner. Null while fresh — or before the device has ever synced (nothing honest
     * to date the cache with).
     */
    private val _staleAsOfMs = MutableStateFlow<Long?>(null)
    val staleAsOfMs: StateFlow<Long?> = _staleAsOfMs.asStateFlow()

    init {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            _sessions.value = UiState.Loading
            try {
                val result = sessionRepository.listSessionsWithFreshness()
                _staleAsOfMs.value =
                    if (result.fromCache) appPreferences.lastSuccessfulSyncMs.first() else null
                _sessions.value = UiState.Success(result.sessions)
            } catch (e: Exception) {
                // An HTTP error (server reachable) keeps erroring rather than degrading to cache.
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
