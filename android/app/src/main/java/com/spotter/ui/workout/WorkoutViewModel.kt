package com.spotter.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SetLogCreate
import com.spotter.data.model.SetLogOut
import com.spotter.data.repository.SessionRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _session = MutableStateFlow<UiState<SessionOut>>(UiState.Loading)
    val session: StateFlow<UiState<SessionOut>> = _session

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                _elapsedSeconds.update { it + 1 }
            }
        }
    }

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

    fun toggleSet(sessionId: String, setLog: SetLogOut) {
        viewModelScope.launch {
            try {
                sessionRepository.logSet(
                    sessionId,
                    SetLogCreate(
                        exerciseId = setLog.exerciseId,
                        setNumber = setLog.setNumber,
                        reps = setLog.reps,
                        weight = setLog.weight,
                        completed = !setLog.completed,
                    ),
                )
                loadSession(sessionId)
            } catch (_: Exception) {}
        }
    }

    fun addSet(sessionId: String, exerciseId: String, lastSet: SetLogOut) {
        viewModelScope.launch {
            try {
                sessionRepository.logSet(
                    sessionId,
                    SetLogCreate(
                        exerciseId = exerciseId,
                        setNumber = lastSet.setNumber + 1,
                        reps = lastSet.reps,
                        weight = lastSet.weight,
                        completed = false,
                    ),
                )
                loadSession(sessionId)
            } catch (_: Exception) {}
        }
    }
}
