package com.spotter.ui.workout

import android.content.Context
import androidx.core.content.ContextCompat
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
    val newPrCount: Int,
)

object WorkoutSummaryStore {
    /** Muscle group breakdown — passed via global store because the list is not
     *  trivially serialisable as a nav route arg. Survives config changes; does
     *  not survive process death (display degrades to empty, which is acceptable). */
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

    // Work timer counts UP while NOT resting and resets to 0 each time a rest ends.
    // Driven off the rest-timer flow so the screen always has a live timer: a "Rest"
    // countdown right after a set, then a "Working" count-up until the next set is
    // completed. Uses flatMapLatest + WhileSubscribed so the infinite loop only runs
    // while something is collecting — same pattern as elapsedSeconds, which prevents
    // runTest's end-of-test drain from hanging.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val workSeconds: StateFlow<Int> = _restTimerSeconds
        .flatMapLatest { rest ->
            if (rest != null) flowOf(0)   // resting — work timer paused/hidden
            else flow {
                var sec = 0
                emit(0)
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

    /**
     * One-tap completion toggle. Persists the set with the reps/weight currently shown
     * in its row, flips its completed flag, and drives the rest timer: completing a set
     * starts the rest countdown; un-completing cancels it. The session state is patched
     * in place (no full reload) so inline edits never flash the loading spinner.
     */
    fun toggleComplete(sessionId: String, setLog: SetLogOut, reps: Int, weightLbs: Double?) {
        val nowCompleted = !setLog.completed
        patchSet(setLog.id) { it.copy(completed = nowCompleted, reps = reps, weight = weightLbs ?: it.weight) }
        if (nowCompleted) startRestTimer(setLog.targetReps, reps) else dismissRestTimer()
        viewModelScope.launch {
            try {
                sessionRepository.updateSet(
                    sessionId, setLog.id,
                    SetLogUpdate(completed = nowCompleted, reps = reps, weight = weightLbs),
                )
            } catch (_: Exception) {}
        }
    }

    /** Persists inline reps/weight edits for a single set (called on field commit). */
    fun editSet(sessionId: String, setLog: SetLogOut, newReps: Int, newWeight: Double?) {
        patchSet(setLog.id) { it.copy(reps = newReps, weight = newWeight ?: it.weight) }
        viewModelScope.launch {
            try {
                sessionRepository.updateSet(
                    sessionId,
                    setLog.id,
                    SetLogUpdate(reps = newReps, weight = newWeight),
                )
            } catch (_: Exception) {}
        }
    }

    /** Replaces a single set in the in-memory session without re-fetching the whole session. */
    private fun patchSet(setId: String, transform: (SetLogOut) -> SetLogOut) {
        val current = (_session.value as? UiState.Success)?.data ?: return
        val newLogs = current.setLogs.map { if (it.id == setId) transform(it) else it }
        _session.value = UiState.Success(current.copy(setLogs = newLogs))
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
        ContextCompat.startForegroundService(context, RestTimerService.startIntent(context, duration))
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

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                sessionRepository.deleteSession(sessionId)
                _navigateBack.emit(Unit)
            } catch (e: Exception) {
                _finishState.value = UiState.Error(e.message ?: "Failed to delete session")
            }
        }
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
                    .sumOf { it.reps * (it.weight ?: 0.0) }
                    .toInt()
                // A new PR = an exercise whose top completed weight this session beats the prior
                // best loaded at session start. Only counts exercises that had a prior best to beat.
                val priors = priorBests.value
                val newPrCount = setLogs
                    .filter { it.completed && it.weight != null }
                    .groupBy { it.exerciseId }
                    .count { (exerciseId, logs) ->
                        val priorBest = priors[exerciseId]?.weight
                        priorBest != null && logs.maxOf { it.weight!! } > priorBest
                    }
                _navigateToSummary.emit(
                    WorkoutSummaryData(
                        durationSeconds = elapsedSeconds.value,
                        doneSets = doneSets,
                        totalSets = totalSets,
                        totalVolumeLb = volumeLb,
                        newPrCount = newPrCount,
                    )
                )
            } catch (e: Exception) {
                _finishState.value = UiState.Error(e.message ?: "Failed to finish workout")
            }
        }
    }
}
