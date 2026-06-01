package com.spotter.ui.workout

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.MuscleGroupSummary
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SessionUpdate
import com.spotter.data.model.SetLogCreate
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.SetLogUpdate
import com.spotter.data.repository.SessionRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkoutSummaryData(
    val durationSeconds: Int,
    val doneSets: Int,
    val totalSets: Int,
    val totalVolumeLb: Int,
)

object WorkoutSummaryStore {
    var muscleGroups: List<MuscleGroupSummary> = emptyList()
}

@HiltViewModel
class WorkoutViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    @ApplicationContext private val context: Context,
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

    // Active lift state — which set is currently being performed
    private val _activeSetId = MutableStateFlow<String?>(null)
    val activeSetId: StateFlow<String?> = _activeSetId.asStateFlow()

    // Rep count for the active set (starts at targetReps, decremented on tap)
    private val _activeSetReps = MutableStateFlow(0)
    val activeSetReps: StateFlow<Int> = _activeSetReps.asStateFlow()

    // Lift timer counts UP while a set is active. Uses flatMapLatest + WhileSubscribed
    // so the infinite loop only runs while something is collecting — same pattern as
    // elapsedSeconds, which prevents runTest's end-of-test drain from hanging.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val liftSeconds: StateFlow<Int> = _activeSetId
        .flatMapLatest { activeId ->
            if (activeId == null) flowOf(0)
            else flow {
                var sec = 0
                while (true) {
                    delay(1000)
                    emit(++sec)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

    fun activateSet(setLog: SetLogOut) {
        _activeSetId.value = setLog.id
        _activeSetReps.value = setLog.targetReps ?: setLog.reps
        // liftSeconds resets automatically: flatMapLatest re-subscribes to a fresh
        // flow starting at 0 whenever _activeSetId changes to a new non-null value.
    }

    fun decrementActiveReps() {
        if (_activeSetReps.value > 1) _activeSetReps.value--
    }

    fun completeActiveSet(sessionId: String) {
        val setId = _activeSetId.value ?: return
        val actualReps = _activeSetReps.value
        val session = (_session.value as? UiState.Success)?.data ?: return
        val setLog = session.setLogs.find { it.id == setId } ?: return
        _activeSetId.value = null   // flatMapLatest switches to flowOf(0), stopping the timer
        viewModelScope.launch {
            try {
                sessionRepository.updateSet(
                    sessionId, setId,
                    SetLogUpdate(completed = true, reps = actualReps),
                )
                loadSession(sessionId)
                // Check for superset continuation — find the next pending set in the same group
                val supersetNext = findNextSupersetSet(session.setLogs, setLog)
                if (supersetNext != null) {
                    activateSet(supersetNext)
                } else {
                    startRestTimer(setLog.targetReps, actualReps)
                }
            } catch (_: Exception) {}
        }
    }

    private fun findNextSupersetSet(allSets: List<SetLogOut>, completedSet: SetLogOut): SetLogOut? {
        val group = completedSet.supersetGroup ?: return null
        return allSets.firstOrNull { sl ->
            !sl.completed && sl.id != completedSet.id && sl.supersetGroup == group
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

    fun startRestTimer(targetReps: Int?, actualReps: Int? = null) {
        val base = when {
            targetReps == null || targetReps <= 5 -> 180
            targetReps <= 12 -> 90
            else -> 60
        }
        val failure = actualReps != null && targetReps != null && actualReps < targetReps
        val duration = if (failure) base + 60 else base
        restTimerJob?.cancel()
        _restTimerSeconds.value = duration
        context.startService(RestTimerService.startIntent(context, duration))
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
        context.startService(RestTimerService.cancelIntent(context))
    }

    fun finishSession(sessionId: String) {
        viewModelScope.launch {
            _finishState.value = UiState.Loading
            try {
                val updated = sessionRepository.updateSession(
                    sessionId,
                    SessionUpdate(
                        status = "completed",
                        durationSeconds = elapsedSeconds.value,
                    ),
                )
                _finishState.value = UiState.Success(Unit)
                WorkoutSummaryStore.muscleGroups = updated.muscleGroups
                val setLogs = updated.setLogs
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
