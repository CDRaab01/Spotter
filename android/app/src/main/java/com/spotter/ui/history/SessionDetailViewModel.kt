package com.spotter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.SessionOut
import com.spotter.data.repository.SessionRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Read-only detail of a past (typically completed) session: the actual reps and weights
 * lifted, per-exercise, plus notes and the muscle-group breakdown. Served by
 * [SessionRepository.getSession], so it works offline from the Room mirror like the rest
 * of session history.
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _session = MutableStateFlow<UiState<SessionOut>>(UiState.Loading)
    val session: StateFlow<UiState<SessionOut>> = _session.asStateFlow()

    fun load(sessionId: String) {
        viewModelScope.launch {
            _session.value = UiState.Loading
            try {
                _session.value = UiState.Success(sessionRepository.getSession(sessionId))
            } catch (e: Exception) {
                _session.value = UiState.Error(e.message ?: "Couldn't load this workout")
            }
        }
    }
}
