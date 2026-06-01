package com.spotter.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SessionUpdate
import com.spotter.data.model.SetLogCreate
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.SetLogUpdate
import com.spotter.data.repository.SessionRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkoutSummaryData(
    val durationSeconds: Int,
    val doneSets: Int,
    val totalSets: Int,
    val totalVolumeLb: Int,
)

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _session = MutableStateFlow<UiState<SessionOut>>(UiState.Loading)
    val session: StateFlow<UiState<SessionOut>> = _session

    // Session timer. Implemented as a WhileSubscribed flow so it only ticks while
    // something is collecting it (the WorkoutScreen). This keeps unit tests that
    // don't observe the timer from leaving an infinite delay loop on the test
    // scheduler, which would hang runTest's end-of-test drain. The running count
    // lives in `elapsed` so it survives brief resubscriptions (e.g. rotation).
    private var elapsed = 0
    val elapsedSeconds: StateFlow<Int> = flow {
        while (true) {
            delay(1000)
            elapsed++
            emit(elapsed)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _finishState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val finishState: StateFlow<UiState<Unit>> = _finishState

    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateBack: SharedFlow<Unit> = _navigateBack

    private val _navigateToSummary = MutableSharedFlow<WorkoutSummaryData>(extraBufferCapacity = 1)
    val navigateToSummary: SharedFlow<WorkoutSummaryData> = _navigateToSummary.asSharedFlow()

    private val _restTimerSeconds = MutableStateFlow<Int?>(null)
    val restTimerSeconds: StateFlow<Int?> = _restTimerSeconds.asStateFlow()

    private val _exerciseNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    val exerciseNotes: StateFlow<Map<String, String>> = _exerciseNotes.asStateFlow()

    private val _priorBests = MutableStateFlow<Map<String, ExercisePrior>>(emptyMap())
    val priorBests: StateFlow<Map<String, ExercisePrior>> = _priorBests.asStateFlow()

    private var restTimerJob: Job? = null

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            _session.value = UiState.Loading
            _session.value = try {
                val data = sessionRepository.getSession(sessionId)
                _exerciseNotes.value = data.exerciseNotes ?: emptyMap()
                UiState.Success(data)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Failed to load session")
            }
            loadPriorBests(sessionId)
        }
    }

    private fun loadPriorBests(sessionId: String) {
        viewModelScope.launch {
            try {
                val bests = sessionRepository.getPriorBests(sessionId)
                _priorBests.value = bests.associateBy { it.exerciseId }
            } catch (_: Exception) {}
        }
    }

    fun toggleSet(sessionId: String, setLog: SetLogOut) {
        val wasIncomplete = !setLog.completed
        viewModelScope.launch {
            try {
                sessionRepository.updateSet(
                    sessionId,
                    setLog.id,
                    SetLogUpdate(completed = !setLog.completed),
                )
                loadSession(sessionId)
                if (wasIncomplete) {
                    startRestTimer(setLog.targetReps)
                }
            } catch (_: Exception) {}
        }
    }

    fun editSet(sessionId: String, setLog: SetLogOut, newReps: Int, newWeight: Double?) {
        viewModelScope.launch {
            try {
                sessionRepository.updateSet(
                    sessionId,
                    setLog.id,
                    SetLogUpdate(reps = newReps, weight = newWeight),
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

    fun saveExerciseNote(sessionId: String, exerciseId: String, note: String) {
        val updated = _exerciseNotes.value.toMutableMap()
        updated[exerciseId] = note
        _exerciseNotes.value = updated
        viewModelScope.launch {
            try {
                sessionRepository.updateSession(
                    sessionId,
                    SessionUpdate(exerciseNotes = updated),
                )
            } catch (_: Exception) {}
        }
    }

    fun startRestTimer(targetReps: Int?) {
        val duration = when {
            targetReps == null || targetReps <= 5 -> 180
            targetReps <= 12 -> 90
            else -> 60
        }
        restTimerJob?.cancel()
        _restTimerSeconds.value = duration
        restTimerJob = viewModelScope.launch {
            var remaining = duration
            while (remaining > 0) {
                delay(1000)
                remaining--
                _restTimerSeconds.value = remaining
            }
            _restTimerSeconds.value = null
        }
    }

    fun dismissRestTimer() {
        restTimerJob?.cancel()
        _restTimerSeconds.value = null
    }

    fun finishSession(sessionId: String) {
        viewModelScope.launch {
            _finishState.value = UiState.Loading
            try {
                sessionRepository.updateSession(
                    sessionId,
                    SessionUpdate(
                        status = "completed",
                        durationSeconds = elapsedSeconds.value,
                    ),
                )
                _finishState.value = UiState.Success(Unit)
                val data = (_session.value as? UiState.Success)?.data
                val setLogs = data?.setLogs ?: emptyList()
                val doneSets = setLogs.count { it.completed }
                val totalSets = setLogs.size
                val volumeLb = setLogs
                    .filter { it.completed }
                    .sumOf { (it.reps * (it.weight ?: 0.0)).toInt() }
                _navigateToSummary.emit(
                    WorkoutSummaryData(
                        durationSeconds = elapsedSeconds.value,
                        doneSets = doneSets,
                        totalSets = totalSets,
                        totalVolumeLb = volumeLb,
                    )
                )
            } catch (e: Exception) {
                _finishState.value = UiState.Error(e.message ?: "Failed to finish workout")
            }
        }
    }
}
