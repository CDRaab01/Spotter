package com.spotter.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.RoutineExerciseIn
import com.spotter.data.model.RoutineOut
import com.spotter.data.model.SessionCreate
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
@OptIn(FlowPreview::class)
class RoutineDetailViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val exerciseRepository: ExerciseRepository,
    private val sessionRepository: SessionRepository,
) : ViewModel() {

    private val _routine = MutableStateFlow<UiState<RoutineOut>>(UiState.Loading)
    val routine: StateFlow<UiState<RoutineOut>> = _routine.asStateFlow()

    private val _isEditing = MutableStateFlow(false)
    val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

    private val _draftExercises = MutableStateFlow<List<DraftExercise>>(emptyList())
    val draftExercises: StateFlow<List<DraftExercise>> = _draftExercises.asStateFlow()

    val searchQuery = MutableStateFlow("")

    val searchResults: StateFlow<List<ExerciseOut>> = searchQuery
        .debounce(300)
        .flatMapLatest { q ->
            flow {
                if (q.isBlank()) {
                    emit(emptyList())
                } else {
                    try {
                        emit(exerciseRepository.search(q))
                    } catch (e: Exception) {
                        emit(emptyList())
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    private val _navigateToWorkout = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToWorkout: SharedFlow<String> = _navigateToWorkout.asSharedFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    fun loadRoutine(routineId: String) {
        viewModelScope.launch {
            _routine.value = UiState.Loading
            try {
                val result = routineRepository.getRoutine(routineId)
                _routine.value = UiState.Success(result)
            } catch (e: Exception) {
                _routine.value = UiState.Error(e.message ?: "Failed to load routine")
            }
        }
    }

    fun startEdit() {
        val current = (_routine.value as? UiState.Success)?.data ?: return
        _draftExercises.value = current.exercises.map { pe ->
            DraftExercise(
                exerciseId = pe.exerciseId,
                name = pe.exerciseName ?: pe.exerciseId,
                targetSets = pe.targetSets,
                targetReps = pe.targetReps,
                targetWeight = pe.targetWeight,
                isBodyweight = pe.isBodyweight,
                order = pe.order,
            )
        }
        _isEditing.value = true
    }

    fun cancelEdit() {
        _draftExercises.value = emptyList()
        _isEditing.value = false
    }

    fun addExercise(ex: ExerciseOut) {
        val list = _draftExercises.value.toMutableList()
        list.add(
            DraftExercise(
                exerciseId = ex.id,
                name = ex.name,
                isBodyweight = ex.equipment == "bodyweight",
                order = list.size,
            )
        )
        _draftExercises.value = list
    }

    fun removeExercise(index: Int) {
        val list = _draftExercises.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _draftExercises.value = list.mapIndexed { i, ex -> ex.copy(order = i) }
        }
    }

    fun updateExercise(index: Int, draft: DraftExercise) {
        val list = _draftExercises.value.toMutableList()
        if (index in list.indices) {
            list[index] = draft
            _draftExercises.value = list
        }
    }

    fun saveEdits(routineId: String) {
        viewModelScope.launch {
            try {
                val exercises = _draftExercises.value.mapIndexed { i, ex ->
                    RoutineExerciseIn(
                        exerciseId = ex.exerciseId,
                        targetSets = ex.targetSets,
                        targetReps = ex.targetReps,
                        targetWeight = ex.targetWeight,
                        isBodyweight = ex.isBodyweight,
                        order = i,
                    )
                }
                routineRepository.updateExercises(routineId, exercises)
                loadRoutine(routineId)
                cancelEdit()
            } catch (e: Exception) {
                // Stay in edit mode so the draft isn't lost; surface the failure.
                _error.value = e.message ?: "Could not save changes"
            }
        }
    }

    fun startWorkout(routineId: String) {
        viewModelScope.launch {
            try {
                val session = sessionRepository.createSession(
                    SessionCreate(
                        routineId = routineId,
                        date = LocalDate.now().toString(),
                    )
                )
                _navigateToWorkout.emit(session.id)
            } catch (e: Exception) {
                // Silently ignored
            }
        }
    }
}
