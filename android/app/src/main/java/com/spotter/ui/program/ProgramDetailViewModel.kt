package com.spotter.ui.program

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.dao.PlannedExerciseDao
import com.spotter.data.local.entity.PlannedExerciseEntity
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.data.model.ProgramDayIn
import com.spotter.data.model.ProgramDaysUpdate
import com.spotter.data.repository.PlanRepository
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
    val planId: String?,
    val planName: String?,
    val label: String,
)

@HiltViewModel
class ProgramDetailViewModel @Inject constructor(
    private val programRepository: ProgramRepository,
    private val planRepository: PlanRepository,
    private val plannedExerciseDao: PlannedExerciseDao,
) : ViewModel() {

    private val _programName = MutableStateFlow("Program")
    val programName: StateFlow<String> = _programName

    private val _days = MutableStateFlow<List<DraftDay>>(emptyList())
    val days: StateFlow<List<DraftDay>> = _days

    /** Per-plan exercise breakdown, keyed by planId, for the day expansion view. */
    private val _dayExercises = MutableStateFlow<Map<String, List<PlannedExerciseEntity>>>(emptyMap())
    val dayExercises: StateFlow<Map<String, List<PlannedExerciseEntity>>> = _dayExercises

    val availablePlans: StateFlow<List<WorkoutPlanEntity>> =
        planRepository.plans.stateIn(
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
            try { planRepository.sync() } catch (_: Exception) {}
            _programName.value = programRepository.programName(id) ?: "Program"
            val days = programRepository.daysFor(id)
            _days.value = days.map {
                DraftDay(planId = it.planId, planName = it.planName, label = it.label)
            }
            // Load each linked plan's exercises for the expandable breakdown.
            _dayExercises.value = days
                .mapNotNull { it.planId }
                .distinct()
                .associateWith { plannedExerciseDao.getByPlanId(it) }
        }
    }

    /** Re-load the breakdown for a single plan after it was edited in PlanDetail. */
    fun refreshPlanExercises(planId: String) {
        viewModelScope.launch {
            val updated = plannedExerciseDao.getByPlanId(planId)
            _dayExercises.value = _dayExercises.value.toMutableMap().apply { put(planId, updated) }
        }
    }

    /** Append a day. Falls back to the plan name (or an ordinal) when no label is typed. */
    fun addDay(plan: WorkoutPlanEntity?, label: String) {
        val trimmed = label.trim()
        val finalLabel = when {
            trimmed.isNotEmpty() -> trimmed
            plan != null -> plan.name
            else -> "Day ${_days.value.size + 1}"
        }
        _days.value = _days.value + DraftDay(plan?.id, plan?.name, finalLabel)
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
                    ProgramDayIn(planId = d.planId, label = d.label, order = i)
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
