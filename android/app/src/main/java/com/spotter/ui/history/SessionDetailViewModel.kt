package com.spotter.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.RoutineCreate
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.SessionOut
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Read-only detail of a past (typically completed) session: the actual reps and weights
 * lifted, per-exercise, plus notes and the muscle-group breakdown. Served by
 * [SessionRepository.getSession], so it works offline from the Room mirror like the rest
 * of session history.
 *
 * It's also where a finished workout becomes a template: **repeat** it today (a fresh session on
 * the same routine) or **save it as a routine** built from what was actually performed.
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val routineRepository: RoutineRepository,
) : ViewModel() {

    private val _session = MutableStateFlow<UiState<SessionOut>>(UiState.Loading)
    val session: StateFlow<UiState<SessionOut>> = _session.asStateFlow()

    /** Local id of a session just created by "Repeat this workout" — the screen navigates to it. */
    private val _startedSessionId = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val startedSessionId: SharedFlow<String> = _startedSessionId.asSharedFlow()

    /** One-line snackbar messages (success or failure) for the two template actions. */
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    /** True while an action is in flight, so the buttons can't be double-tapped. */
    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    fun clearActionMessage() {
        _actionMessage.value = null
    }

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

    /** Start today's workout from the same routine this session used. */
    fun repeatWorkout() {
        val session = (_session.value as? UiState.Success)?.data ?: return
        val routineId = session.routineId ?: return
        if (_working.value) return
        viewModelScope.launch {
            _working.value = true
            try {
                // The session may carry the routine's SERVER id (server-served read); the session
                // repository keys everything off local ids, so translate before creating.
                val local = routineRepository.localRoutineId(routineId)
                val created = sessionRepository.createSession(
                    SessionCreate(routineId = local, date = LocalDate.now().toString())
                )
                _startedSessionId.emit(created.id)
            } catch (e: Exception) {
                _actionMessage.value = e.message ?: "Couldn't start that workout. Try again."
            } finally {
                _working.value = false
            }
        }
    }

    /** Save what was actually performed as a new, reusable routine. */
    fun saveAsRoutine() {
        val session = (_session.value as? UiState.Success)?.data ?: return
        if (_working.value) return
        val exercises = routineExercisesFromSession(session)
        if (exercises.isEmpty()) {
            _actionMessage.value = "No completed sets to save as a routine."
            return
        }
        val name = copiedRoutineName(session)
        viewModelScope.launch {
            _working.value = true
            try {
                val created = routineRepository.createRoutine(
                    RoutineCreate(name = name, source = "manual", exercises = exercises)
                )
                _actionMessage.value = "Saved \"${created.name}\" as a routine"
            } catch (e: Exception) {
                _actionMessage.value = e.message ?: "Couldn't save that routine. Try again."
            } finally {
                _working.value = false
            }
        }
    }
}
