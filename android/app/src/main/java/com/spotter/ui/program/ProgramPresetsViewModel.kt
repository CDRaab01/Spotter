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

/**
 * The outcome of applying a preset. [dropped] names the exercises (and, in brackets, whole days)
 * the local catalog couldn't resolve — previously they were silently `mapNotNull`-ed away, so a
 * program could land quietly missing lifts.
 */
data class PresetApplyResult(
    val programName: String,
    val activated: Boolean,
    val dropped: List<String> = emptyList(),
)

/** The snackbar line for an applied preset — pure so both preset screens phrase it identically. */
fun presetAppliedMessage(result: PresetApplyResult): String {
    val base = "\"${result.programName}\" added" + if (result.activated) " & activated" else ""
    if (result.dropped.isEmpty()) return base
    val listed = result.dropped.take(3).joinToString(", ")
    val more = result.dropped.size - 3
    return "$base · couldn't add $listed" + if (more > 0) " +$more more" else ""
}

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

    private val _applied = MutableSharedFlow<PresetApplyResult>(extraBufferCapacity = 1)
    val applied: SharedFlow<PresetApplyResult> = _applied.asSharedFlow()

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val error: SharedFlow<String> = _error.asSharedFlow()

    /**
     * Resolves the preset's exercises (by name) to ids, then creates the program via the shared
     * accept-program endpoint and refreshes local caches. Rest days (no exercises) are sent as
     * labelled days with no exercises — the server turns those into program days with no routine,
     * which is what makes the preset's prescribed cadence real.
     *
     * @param activate when false the program is saved without touching the currently active one.
     */
    fun applyPreset(preset: PresetProgram, activate: Boolean = true) {
        if (_applyingId.value != null) return
        viewModelScope.launch {
            _applyingId.value = preset.id
            try {
                // listAll() is mirror-backed: offline it resolves against the cached exercise
                // catalog, so name→id resolution works without connectivity (the accept call
                // below still needs the server and errors normally when it's unreachable).
                val byName = exerciseRepository.listAll()
                    .associateBy { it.name.trim().lowercase() }

                val dropped = mutableListOf<String>()
                val days = mutableListOf<SuggestedProgramDay>()
                preset.days.forEach { day ->
                    if (day.isRest) {
                        days += SuggestedProgramDay(label = day.label, exercises = emptyList(), order = 0)
                        return@forEach
                    }
                    val exercises = day.exercises.mapNotNull { ex ->
                        val match = byName[ex.name.trim().lowercase()]
                        if (match == null) {
                            dropped += ex.name
                            null
                        } else {
                            RoutineExerciseIn(
                                exerciseId = match.id,
                                targetSets = ex.sets,
                                targetReps = ex.reps,
                                targetWeight = ex.weight,
                                isBodyweight = ex.isBodyweight,
                                order = 0,
                            )
                        }
                    }.mapIndexed { order, pe -> pe.copy(order = order) }
                    // A training day whose exercises all failed to resolve must NOT be sent as an
                    // empty day — that would silently become a rest day.
                    if (exercises.isEmpty()) {
                        dropped += "all of ${day.label}"
                    } else {
                        days += SuggestedProgramDay(label = day.label, exercises = exercises, order = 0)
                    }
                }
                val ordered = days.mapIndexed { i, d -> d.copy(order = i) }

                if (ordered.none { it.exercises.isNotEmpty() }) {
                    _error.emit("Couldn't match this preset to your exercise library.")
                    return@launch
                }

                val result = aiRepository.acceptProgram(
                    AcceptProgramRequest(
                        name = preset.displayName,
                        days = ordered,
                        description = preset.description,
                        source = "preset",
                        activate = activate,
                    )
                )
                runCatching { programRepository.sync() }
                runCatching { routineRepository.sync() }
                _applied.emit(PresetApplyResult(result.name, activate, dropped.toList()))
            } catch (e: Exception) {
                _error.emit(e.message ?: "Couldn't add this program. Try again.")
            } finally {
                _applyingId.value = null
            }
        }
    }
}
