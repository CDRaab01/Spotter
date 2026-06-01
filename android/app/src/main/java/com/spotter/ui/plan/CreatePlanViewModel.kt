package com.spotter.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.PlanCreate
import com.spotter.data.model.PlannedExerciseIn
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.PlanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DraftExercise(
    val exerciseId: String,
    val name: String,
    val targetSets: Int = 3,
    val targetReps: Int = 8,
    val targetWeight: Double? = null,
    val isBodyweight: Boolean = false,
    val order: Int = 0,
)

@HiltViewModel
@OptIn(FlowPreview::class)
class CreatePlanViewModel @Inject constructor(
    private val planRepository: PlanRepository,
    private val exerciseRepository: ExerciseRepository,
) : ViewModel() {

    val planName = MutableStateFlow("")

    private val _exercises = MutableStateFlow<List<DraftExercise>>(emptyList())
    val exercises: StateFlow<List<DraftExercise>> = _exercises.asStateFlow()

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

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _navigateBack = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val navigateBack: SharedFlow<Unit> = _navigateBack

    fun addExercise(ex: ExerciseOut) {
        val list = _exercises.value.toMutableList()
        list.add(
            DraftExercise(
                exerciseId = ex.id,
                name = ex.name,
                isBodyweight = ex.equipment == "bodyweight",
                order = list.size,
            )
        )
        _exercises.value = list
    }

    fun removeExercise(index: Int) {
        val list = _exercises.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _exercises.value = list.mapIndexed { i, ex -> ex.copy(order = i) }
        }
    }

    fun updateExercise(index: Int, draft: DraftExercise) {
        val list = _exercises.value.toMutableList()
        if (index in list.indices) {
            list[index] = draft
            _exercises.value = list
        }
    }

    fun savePlan() {
        if (planName.value.isBlank() || _exercises.value.isEmpty()) return
        viewModelScope.launch {
            try {
                planRepository.createPlan(
                    PlanCreate(
                        name = planName.value.trim(),
                        source = "manual",
                        exercises = _exercises.value.mapIndexed { i, ex ->
                            PlannedExerciseIn(
                                exerciseId = ex.exerciseId,
                                targetSets = ex.targetSets,
                                targetReps = ex.targetReps,
                                targetWeight = ex.targetWeight,
                                isBodyweight = ex.isBodyweight,
                                order = i,
                            )
                        },
                    )
                )
                _navigateBack.emit(Unit)
            } catch (e: Exception) {
                _saveError.value = e.message ?: "Failed to save plan."
            }
        }
    }

    fun clearError() {
        _saveError.value = null
    }
}
