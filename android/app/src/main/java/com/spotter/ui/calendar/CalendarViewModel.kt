package com.spotter.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.dao.CardioSessionDao
import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.model.CalendarEntry
import com.spotter.data.model.SessionCreate
import com.spotter.data.repository.CalendarRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.cardio.CardioPrograms
import com.spotter.ui.cardio.CardioSchedule
import com.spotter.util.AppPreferences
import com.spotter.util.ProjectionDay
import com.spotter.util.SessionAnchor
import com.spotter.util.UiState
import com.spotter.util.UpcomingWorkout
import com.spotter.util.WorkoutProjection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendarRepository: CalendarRepository,
    private val sessionRepository: SessionRepository,
    private val programRepository: ProgramRepository,
    private val routineRepository: RoutineRepository,
    private val appPreferences: AppPreferences,
    private val sessionDao: WorkoutSessionDao,
    private val programDao: WorkoutProgramDao,
    private val programDayDao: ProgramDayDao,
    private val routineExerciseDao: RoutineExerciseDao,
    private val cardioSessionDao: CardioSessionDao,
) : ViewModel() {

    private val _displayedMonth = MutableStateFlow(YearMonth.now())
    val displayedMonth: StateFlow<YearMonth> = _displayedMonth.asStateFlow()

    private val _entries = MutableStateFlow<UiState<List<CalendarEntry>>>(UiState.Idle)
    val entries: StateFlow<UiState<List<CalendarEntry>>> = _entries

    /** Projected upcoming workouts that fall within the displayed month (no real session yet). */
    private val _projected = MutableStateFlow<List<UpcomingWorkout>>(emptyList())
    val projected: StateFlow<List<UpcomingWorkout>> = _projected.asStateFlow()

    /** False when there's nothing scheduled (no active strength OR cardio program) — empty-state hint. */
    private val _hasActiveProgram = MutableStateFlow(true)
    val hasActiveProgram: StateFlow<Boolean> = _hasActiveProgram.asStateFlow()

    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    val selectedDate: StateFlow<LocalDate?> = _selectedDate.asStateFlow()

    private val _navigateToWorkout = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToWorkout: SharedFlow<String> = _navigateToWorkout.asSharedFlow()

    init {
        loadMonth(YearMonth.now(), sync = true)
    }

    /** Re-sync sources and recompute the current month (called on screen resume). */
    fun refresh() = loadMonth(_displayedMonth.value, sync = true)

    fun loadMonth(month: YearMonth, sync: Boolean = false) {
        _displayedMonth.value = month
        _selectedDate.value = null
        _projected.value = emptyList()
        viewModelScope.launch {
            // Pull the active program + its routines (and push pending sessions) so the
            // schedule reflects server state, not just whatever happens to be cached.
            if (sync) {
                runCatching { programRepository.sync() }
                runCatching { routineRepository.sync() }
                runCatching { sessionRepository.syncPending() }
            }
            _entries.value = UiState.Loading
            val loaded = try {
                val from = month.atDay(1).toString()
                val to = month.atEndOfMonth().toString()
                calendarRepository.getCalendar(from, to)
            } catch (e: Exception) {
                _entries.value = UiState.Error(e.message ?: "Failed to load calendar")
                return@launch
            }
            _entries.value = UiState.Success(loaded)
            _projected.value = computeProjected(month, loaded)
        }
    }

    /**
     * Projects upcoming workouts that fall inside [month], excluding any date that already has a
     * real session (a logged session always wins over a projection).
     */
    private suspend fun computeProjected(
        month: YearMonth,
        entries: List<CalendarEntry>,
    ): List<UpcomingWorkout> {
        val active = programDao.getActive()
        val cardioId = appPreferences.activeCardioProgramId.first()
        _hasActiveProgram.value = active != null || cardioId != null

        val today = LocalDate.now()
        val monthStart = month.atDay(1)
        val monthEnd = month.atEndOfMonth()
        if (monthEnd.isBefore(today)) return emptyList()

        val realDates = entries.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet()
        val strength = computeProjectedStrength(active, today, monthStart, monthEnd, realDates)
        val cardio = computeProjectedCardio(cardioId, today, monthStart, monthEnd)
        return (strength + cardio).sortedBy { it.date }
    }

    private suspend fun computeProjectedStrength(
        active: com.spotter.data.local.entity.WorkoutProgramEntity?,
        today: LocalDate,
        monthStart: LocalDate,
        monthEnd: LocalDate,
        realDates: Set<LocalDate>,
    ): List<UpcomingWorkout> {
        if (active == null) return emptyList()
        val days = programDayDao.getByProgram(active.id)
            .map { ProjectionDay(it.routineId, it.label, it.routineName) }
        if (days.isEmpty()) return emptyList()

        val cadence = appPreferences.workoutCadenceDays.first()
        val effectiveStep = WorkoutProjection.effectiveCadence(cadence, days)
        val anchor = sessionDao.getAll()
            .filter { it.status == "completed" || it.status == "in_progress" }
            .mapNotNull { s ->
                runCatching { LocalDate.parse(s.date) }.getOrNull()
                    ?.let { SessionAnchor(it, s.routineId, s.status) }
            }
            .maxByOrNull { it.date }

        // Enough slots to reach the end of the visible month, with headroom for cycling.
        val span = ChronoUnit.DAYS.between(today, monthEnd).coerceAtLeast(0)
        val count = (span / effectiveStep + days.size + 2).toInt().coerceIn(1, 200)

        return WorkoutProjection.project(today, cadence, anchor, days, count)
            .filter { !it.date.isBefore(monthStart) && !it.date.isAfter(monthEnd) && it.date !in realDates }
            .map { slot ->
                val lifts = slot.routineId
                    ?.let { routineExerciseDao.getByRoutineId(it).take(4) }
                    ?: emptyList()
                UpcomingWorkout(slot.date, slot.label, slot.routineId, slot.routineName, lifts, slot.dayIndex)
            }
    }

    private suspend fun computeProjectedCardio(
        cardioId: String?,
        today: LocalDate,
        monthStart: LocalDate,
        monthEnd: LocalDate,
    ): List<UpcomingWorkout> {
        if (cardioId == null) return emptyList()
        val program = CardioPrograms.byId(cardioId)?.takeIf { it.weeks != null } ?: return emptyList()
        val sessions = cardioSessionDao.observeByProgram(cardioId).first()
        // Cardio runs are completion-driven, so projecting the full remaining plan covers any month.
        return CardioSchedule.upcoming(program, sessions, today, count = CardioSchedule.orderedDays(program).size)
            .filter { !it.date.isBefore(monthStart) && !it.date.isAfter(monthEnd) }
    }

    fun nextMonth() = loadMonth(_displayedMonth.value.plusMonths(1))
    fun prevMonth() = loadMonth(_displayedMonth.value.minusMonths(1))

    fun selectDate(date: LocalDate) {
        _selectedDate.value = if (_selectedDate.value == date) null else date
    }

    /** Starts a workout today from a projected day and navigates into the session. */
    fun startProjectedSession(routineId: String) {
        viewModelScope.launch {
            try {
                val session = sessionRepository.createSession(
                    SessionCreate(routineId = routineId, date = LocalDate.now().toString()),
                )
                _navigateToWorkout.emit(session.id)
            } catch (_: Exception) {
                // Surfacing handled by the screen staying put; user can retry.
            }
        }
    }
}
