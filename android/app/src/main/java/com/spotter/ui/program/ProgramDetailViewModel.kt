package com.spotter.ui.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.data.model.ProgramDayIn
import com.spotter.data.model.ProgramDaysUpdate
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.ProgramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A program day being edited before it is persisted via replaceDays. */
data class DraftDay(
    val routineId: String?,
    val routineName: String?,
    val label: String,
)

@HiltViewModel
class ProgramDetailViewModel @Inject constructor(
    private val programRepository: ProgramRepository,
    private val routineRepository: RoutineRepository,
    private val routineExerciseDao: RoutineExerciseDao,
) : ViewModel() {

    private val _programName = MutableStateFlow("Program")
    val programName: StateFlow<String> = _programName

    /** The mirrored program row, for the periodization header (null until [load] resolves it). */
    private val _program = MutableStateFlow<WorkoutProgramEntity?>(null)
    val program: StateFlow<WorkoutProgramEntity?> = _program

    private val _days = MutableStateFlow<List<DraftDay>>(emptyList())
    val days: StateFlow<List<DraftDay>> = _days

    /** Per-routine exercise breakdown, keyed by routineId, for the day expansion view. */
    private val _dayExercises = MutableStateFlow<Map<String, List<RoutineExerciseEntity>>>(emptyMap())
    val dayExercises: StateFlow<Map<String, List<RoutineExerciseEntity>>> = _dayExercises

    val availableRoutines: StateFlow<List<WorkoutRoutineEntity>> =
        routineRepository.routines.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )

    private val _saved = MutableSharedFlow<Unit>()
    val saved: SharedFlow<Unit> = _saved

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var programId: String = ""

    fun load(id: String) {
        programId = id
        viewModelScope.launch {
            try { programRepository.sync() } catch (_: Exception) {}
            try { routineRepository.sync() } catch (_: Exception) {}
            val program = programRepository.program(id)
            _program.value = program
            _programName.value = program?.name ?: "Program"
            val days = programRepository.daysFor(id)
            _days.value = days.map {
                DraftDay(routineId = it.routineId, routineName = it.routineName, label = it.label)
            }
            // Load each linked routine's exercises for the expandable breakdown.
            _dayExercises.value = days
                .mapNotNull { it.routineId }
                .distinct()
                .associateWith { routineExerciseDao.getByRoutineId(it) }
        }
    }

    /** Re-load the breakdown for a single routine after it was edited in RoutineDetail. */
    fun refreshRoutineExercises(routineId: String) {
        viewModelScope.launch {
            val updated = routineExerciseDao.getByRoutineId(routineId)
            _dayExercises.value = _dayExercises.value.toMutableMap().apply { put(routineId, updated) }
        }
    }

    /** Append a day. Falls back to the routine name (or an ordinal) when no label is typed. */
    fun addDay(routine: WorkoutRoutineEntity?, label: String) {
        val trimmed = label.trim()
        val finalLabel = when {
            trimmed.isNotEmpty() -> trimmed
            routine != null -> routine.name
            else -> "Day ${_days.value.size + 1}"
        }
        _days.value = _days.value + DraftDay(routine?.id, routine?.name, finalLabel)
    }

    fun removeDay(index: Int) {
        _days.value = _days.value.toMutableList().also {
            if (index in it.indices) it.removeAt(index)
        }
    }

    /** Move the day at [index] by [delta] positions (e.g. -1 up, +1 down). */
    fun moveDay(index: Int, delta: Int) {
        val list = _days.value
        val target = index + delta
        if (index !in list.indices || target !in list.indices) return
        _days.value = list.toMutableList().also {
            val tmp = it[index]
            it[index] = it[target]
            it[target] = tmp
        }
    }

    fun save() {
        viewModelScope.launch {
            try {
                val daysIn = _days.value.mapIndexed { i, d ->
                    ProgramDayIn(routineId = d.routineId, label = d.label, order = i)
                }
                programRepository.replaceDays(programId, ProgramDaysUpdate(daysIn))
                _saved.emit(Unit)
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not save days"
            }
        }
    }

    fun clearError() { _error.value = null }
}
