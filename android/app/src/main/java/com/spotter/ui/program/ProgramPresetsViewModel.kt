package com.spotter.ui.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.model.AcceptProgramRequest
import com.spotter.data.model.RoutineExerciseIn
import com.spotter.data.model.SuggestedProgramDay
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.ProgramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProgramPresetsViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    private val aiRepository: AiRepository,
    private val programRepository: ProgramRepository,
    private val routineRepository: RoutineRepository,
) : ViewModel() {

    /** Id of the preset currently being applied (drives a per-card spinner), or null. */
    private val _applyingId = MutableStateFlow<String?>(null)
    val applyingId: StateFlow<String?> = _applyingId.asStateFlow()

    private val _applied = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val applied: SharedFlow<String> = _applied.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    /**
     * Resolves the preset's exercises (by name) to ids, then creates + activates the
     * program via the shared accept-program endpoint and refreshes local caches.
     */
    fun applyPreset(preset: PresetProgram) {
        if (_applyingId.value != null) return
        viewModelScope.launch {
            _applyingId.value = preset.id
            try {
                val byName = exerciseRepository.search("")
                    .associateBy { it.name.trim().lowercase() }

                val days = preset.days.mapIndexed { i, day ->
                    val exercises = day.exercises.mapNotNull { ex ->
                        byName[ex.name.trim().lowercase()]?.let { match ->
                            RoutineExerciseIn(
                                exerciseId = match.id,
                                targetSets = ex.sets,
                                targetReps = ex.reps,
                                targetWeight = null,
                                isBodyweight = ex.isBodyweight,
                                order = 0,
                            )
                        }
                    }.mapIndexed { order, pe -> pe.copy(order = order) }
                    SuggestedProgramDay(label = day.label, exercises = exercises, order = i)
                }

                if (days.none { it.exercises.isNotEmpty() }) {
                    _error.emit("Couldn't match this preset to your exercise library.")
                    return@launch
                }

                val result = aiRepository.acceptProgram(
                    AcceptProgramRequest(name = preset.displayName, days = days)
                )
                runCatching { programRepository.sync() }
                runCatching { routineRepository.sync() }
                _applied.emit(result.name)
            } catch (e: Exception) {
                _error.emit(e.message ?: "Couldn't add this program. Try again.")
            } finally {
                _applyingId.value = null
            }
        }
    }
}
