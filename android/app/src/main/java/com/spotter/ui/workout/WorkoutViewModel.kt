package com.spotter.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.MuscleGroupSummary
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SessionUpdate
import com.spotter.data.model.SetLogCreate
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.SetLogUpdate
import com.spotter.data.model.SuggestedAdjustmentAction
import com.spotter.data.repository.SessionRepository
import com.spotter.util.TimeProvider
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val workoutTimer: WorkoutTimerController,
    private val time: TimeProvider,
) : ViewModel() {

    private val _session = MutableStateFlow<UiState<SessionOut>>(UiState.Loading)
    val session: StateFlow<UiState<SessionOut>> = _session

    // Session elapsed clock. Recomputed each tick from the persisted `startedAtMs` epoch anchor
    // (read from Room in loadSession) rather than incrementing a counter, so it's drift-free and
    // immediately correct after backgrounding, screen-off, or process death — and matches the
    // notification chronometer and the bottom-bar clock, which derive from the same anchor.
    // WhileSubscribed keeps the loop from hanging runTest when nothing observes it.
    private var startedAtMs: Long? = null
    val elapsedSeconds: StateFlow<Int> = flow {
        while (true) {
            emit(currentElapsedSec())
            delay(1000)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private fun currentElapsedSec(): Int {
        val anchor = startedAtMs ?: return 0
        return ((time.nowMs() - anchor) / 1000L).coerceAtLeast(0L).toInt()
    }

    private val _finishState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val finishState: StateFlow<UiState<Unit>> = _finishState

    /** One-line failure messages surfaced by the screen's snackbar (finish/delete/apply). */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    fun clearActionError() {
        _actionError.value = null
    }

    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateBack: SharedFlow<Unit> = _navigateBack

    private val _navigateToSummary = MutableSharedFlow<WorkoutSummaryData>(extraBufferCapacity = 1)
    val navigateToSummary: SharedFlow<WorkoutSummaryData> = _navigateToSummary.asSharedFlow()

    private val _restTimerSeconds = MutableStateFlow<Int?>(null)
    val restTimerSeconds: StateFlow<Int?> = _restTimerSeconds.asStateFlow()

    // Length of the rest the current countdown started from, so the UI ring can show
    // real progress (null while working).
    private val _restDurationSeconds = MutableStateFlow<Int?>(null)
    val restDurationSeconds: StateFlow<Int?> = _restDurationSeconds.asStateFlow()

    private val _exerciseNotes = MutableStateFlow<Map<String, String>>(emptyMap())
    val exerciseNotes: StateFlow<Map<String, String>> = _exerciseNotes.asStateFlow()

    private val _priorBests = MutableStateFlow<Map<String, ExercisePrior>>(emptyMap())
    val priorBests: StateFlow<Map<String, ExercisePrior>> = _priorBests.asStateFlow()

    // Work timer counts UP while NOT resting and resets to 0 each time a rest ends.
    // Driven off the controller's rest state so the screen always has a live timer: a "Rest"
    // countdown right after a set, then a "Working" count-up (recomputed from the controller's
    // drift-free anchor) until the next set is completed. flatMapLatest + WhileSubscribed keep the
    // infinite loop running only while something collects — preventing runTest's drain from hanging.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val workSeconds: StateFlow<Int> = workoutTimer.restState
        .flatMapLatest { rest ->
            if (rest != null) flowOf(0)   // resting — work timer paused/hidden
            else flow {
                while (true) {
                    emit(workoutTimer.workElapsedSec())
                    delay(1000)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        // The controller is the single source of truth for the rest countdown; mirror it into the
        // UI-facing state so the on-screen ring stays in lock-step with the notification and never
        // drifts (no parallel countdown lives here anymore).
        viewModelScope.launch {
            workoutTimer.restState.collect { rest ->
                _restTimerSeconds.value = rest?.remainingSec
                _restDurationSeconds.value = rest?.durationSec
            }
        }
    }

    // Which session is loaded. loadSession is re-invoked on rotation/resume for the same session,
    // but a reused VM loading a *different* session must re-read that session's start anchor.
    private var timerSessionId: String? = null

    fun loadSession(sessionId: String) {
        if (timerSessionId != sessionId) {
            timerSessionId = sessionId
            startedAtMs = null
        }
        viewModelScope.launch {
            startedAtMs = try {
                sessionRepository.getStartedAtMs(sessionId)
            } catch (_: Exception) {
                startedAtMs
            }
        }
        viewModelScope.launch {
            // Only show the spinner when there's nothing on screen yet — an ON_RESUME
            // reload (e.g. returning from the coach chat) must not flash over live data.
            if (_session.value !is UiState.Success) _session.value = UiState.Loading
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
        // Reflect immediately for instant UI; the controller (single source) then drives the
        // drift-free countdown, owns the wake-lock, and fires the end-of-rest cue even when
        // backgrounded. No countdown loop lives here, so the ring can't drift out of sync.
        _restTimerSeconds.value = duration
        _restDurationSeconds.value = duration
        workoutTimer.startRest(duration)
    }

    fun dismissRestTimer() {
        _restTimerSeconds.value = null
        _restDurationSeconds.value = null
        workoutTimer.dismissRest()
    }

    /**
     * Applies the progression engine's suggestion for one exercise: this session's incomplete
     * sets take the suggested weight (and reps, when suggested) now, and the routine's target
     * is updated so the NEXT session pre-fills at the new load instead of the original
     * prescription forever. Reuses the adjustment-apply path — user-initiated, same trust
     * model and the same incomplete-sets-only invariant as an AI Apply card.
     */
    fun applyProgression(sessionId: String, prior: ExercisePrior) {
        val weight = prior.suggestedWeight ?: return
        viewModelScope.launch {
            try {
                sessionRepository.applyAdjustment(
                    sessionId,
                    listOf(
                        SuggestedAdjustmentAction(
                            type = "adjust_weight",
                            exerciseId = prior.exerciseId,
                            exerciseName = prior.exerciseName ?: "",
                            weight = weight,
                            reps = prior.suggestedReps,
                            summary = prior.suggestedReason ?: "Apply progression suggestion",
                        )
                    ),
                    applyToRoutine = true,
                )
                loadSession(sessionId)
            } catch (e: Exception) {
                _actionError.value =
                    "Couldn't apply the suggestion. Check your connection and try again."
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                sessionRepository.deleteSession(sessionId)
                _navigateBack.emit(Unit)
            } catch (e: Exception) {
                _actionError.value = "Couldn't delete the session. Try again."
            }
        }
    }

    fun finishSession(sessionId: String) {
        // End any in-progress rest so its wake-lock is released and no cue fires post-finish.
        workoutTimer.dismissRest()
        viewModelScope.launch {
            _finishState.value = UiState.Loading
            try {
                val updated = sessionRepository.updateSession(
                    sessionId,
                    SessionUpdate(
                        status = "completed",
                        durationSeconds = currentElapsedSec(),
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
                        durationSeconds = currentElapsedSec(),
                        doneSets = doneSets,
                        totalSets = totalSets,
                        totalVolumeLb = volumeLb,
                        newPrCount = newPrCount,
                    )
                )
            } catch (e: Exception) {
                // finishState is only read for its Loading flag — an Error left there was
                // invisible, so a failed finish silently kept the workout open. Snackbar it.
                _finishState.value = UiState.Idle
                _actionError.value = "Couldn't finish the workout. Check your connection and try again."
            }
        }
    }
}
