package com.spotter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.data.model.AcceptProgramRequest
import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.ChatMessage
import com.spotter.data.model.ChatRequest
import com.spotter.data.model.RoutineCreate
import com.spotter.data.model.RoutineUpdate
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.ProgramDayOut
import com.spotter.data.remote.ApiService
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.MetricRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.data.repository.SessionRepository
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val routineRepository: RoutineRepository,
    private val sessionRepository: SessionRepository,
    private val metricRepository: MetricRepository,
    private val aiRepository: AiRepository,
    private val programRepository: ProgramRepository,
    private val appPreferences: AppPreferences,
    private val api: ApiService,
    private val sessionDao: WorkoutSessionDao,
    private val programDao: WorkoutProgramDao,
    private val programDayDao: ProgramDayDao,
    private val routineExerciseDao: RoutineExerciseDao,
) : ViewModel() {

    private val _routines = MutableStateFlow<UiState<List<WorkoutRoutineEntity>>>(UiState.Loading)
    val routines: StateFlow<UiState<List<WorkoutRoutineEntity>>> = _routines

    private val _startState = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val startState: StateFlow<UiState<Unit>> = _startState

    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError

    private val _navigateToWorkout = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToWorkout: SharedFlow<String> = _navigateToWorkout.asSharedFlow()

    private val _generatingPlan = MutableStateFlow(false)
    val generatingPlan: StateFlow<Boolean> = _generatingPlan.asStateFlow()

    private val _streak = MutableStateFlow(0)
    val streak: StateFlow<Int> = _streak.asStateFlow()

    private val _weeklyActiveMinutes = MutableStateFlow(0)
    val weeklyActiveMinutes: StateFlow<Int> = _weeklyActiveMinutes.asStateFlow()

    /** Active minutes per day Monday→Sunday of the current week (zeros for days without work). */
    private val _weeklyMinutesByDay = MutableStateFlow<List<Float>>(emptyList())
    val weeklyMinutesByDay: StateFlow<List<Float>> = _weeklyMinutesByDay.asStateFlow()

    private val _activeProgramId = MutableStateFlow<String?>(null)
    val activeProgramId: StateFlow<String?> = _activeProgramId.asStateFlow()

    private val _nextProgramDay = MutableStateFlow<ProgramDayOut?>(null)
    val nextProgramDay: StateFlow<ProgramDayOut?> = _nextProgramDay.asStateFlow()

    private val _upcoming = MutableStateFlow<UiState<List<UpcomingWorkout>>>(UiState.Loading)
    val upcoming: StateFlow<UiState<List<UpcomingWorkout>>> = _upcoming.asStateFlow()

    private val _greeting = MutableStateFlow(greetingForTime(LocalTime.now()))
    val greeting: StateFlow<String> = _greeting.asStateFlow()

    private val _routineExercises = MutableStateFlow<Map<String, List<RoutineExerciseEntity>>>(emptyMap())
    val routineExercises: StateFlow<Map<String, List<RoutineExerciseEntity>>> = _routineExercises.asStateFlow()

    /** Latest logged bodyweight in pounds, or null when none has been recorded. */
    private val _bodyweight = MutableStateFlow<Double?>(null)
    val bodyweight: StateFlow<Double?> = _bodyweight.asStateFlow()

    private var autoGenerateTriggered = false

    init {
        observeRoutines()
        observeBodyweight()
        sync()
        loadStats()
        loadUpcoming()
        loadGreeting()
    }

    /**
     * Personalises the greeting with the user's first name (e.g. "Good afternoon, Sonic").
     * Falls back to the plain time-of-day greeting if the name can't be fetched (offline).
     */
    private fun loadGreeting() {
        viewModelScope.launch {
            val firstName = runCatching { firstNameOf(api.getMe().name) }.getOrNull()
            if (!firstName.isNullOrBlank()) {
                _greeting.value = greetingForTime(LocalTime.now(), firstName)
            }
        }
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                val sessions = sessionRepository.listSessions()
                val completed = sessions.filter { it.status == "completed" }
                val completedDates = completed
                    .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    .toSet()

                val today = LocalDate.now()
                // Rest days are transparent to the streak: they don't increment the count
                // but they don't break the chain either. Also treated as "done" for the
                // grace-day anchor so a rest day today doesn't bump the anchor back.
                val restDayDates = computeRestDayDates(today)

                // Streak: consecutive workout days, skipping scheduled rest days.
                // Anchor at today when trained or resting today; else yesterday (grace day).
                var day = when {
                    completedDates.contains(today) -> today
                    restDayDates.contains(today) -> today
                    else -> today.minusDays(1)
                }
                var streak = 0
                while (completedDates.contains(day) || restDayDates.contains(day)) {
                    if (!restDayDates.contains(day)) streak++
                    day = day.minusDays(1)
                }
                _streak.value = streak

                // Active minutes: sum of completed-session durations within the current
                // week (Monday → today).
                val weekStart = today.with(DayOfWeek.MONDAY)
                val thisWeek = completed.mapNotNull { s ->
                    val date = runCatching { LocalDate.parse(s.date) }.getOrNull()
                        ?: return@mapNotNull null
                    if (!date.isBefore(weekStart) && !date.isAfter(today)) {
                        date to (s.durationSeconds ?: 0)
                    } else {
                        null
                    }
                }
                _weeklyActiveMinutes.value = thisWeek.sumOf { it.second } / 60
                _weeklyMinutesByDay.value = (0..6).map { offset ->
                    val day = weekStart.plusDays(offset.toLong())
                    thisWeek.filter { it.first == day }.sumOf { it.second } / 60f
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun computeRestDayDates(today: LocalDate): Set<LocalDate> {
        return try {
            val active = programDao.getActive() ?: return emptySet()
            val days = programDayDao.getByProgram(active.id)
                .map { ProjectionDay(it.routineId, it.label, it.routineName) }
            if (days.none { it.routineId == null }) return emptySet()
            val anchor = sessionDao.getAll()
                .filter { it.status == "completed" || it.status == "in_progress" }
                .mapNotNull { s ->
                    runCatching { LocalDate.parse(s.date) }.getOrNull()
                        ?.let { SessionAnchor(it, s.routineId, s.status) }
                }
                .maxByOrNull { it.date }
            WorkoutProjection.restDayDatesInRange(anchor, days, today.minusDays(90), today)
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** Re-pull stats and upcoming workouts (called on Home resume). */
    fun refresh() {
        loadStats()
        loadUpcoming()
    }

    private fun observeBodyweight() {
        viewModelScope.launch {
            metricRepository.metrics.collect { metrics ->
                _bodyweight.value = metrics.maxByOrNull { it.date }?.weight
            }
        }
    }

    /**
     * Re-derives the next two upcoming workouts from cached data: the active program's ordered
     * days, the cadence preference, and the most recent session (completed or in-progress) as the
     * anchor. Because it re-runs after every sync, completing or starting a workout reshuffles the
     * schedule automatically.
     */
    private fun loadUpcoming() {
        viewModelScope.launch {
            try {
                val active = programDao.getActive()
                _activeProgramId.value = active?.id
                if (active == null) {
                    _upcoming.value = UiState.Success(emptyList())
                    return@launch
                }
                val days = programDayDao.getByProgram(active.id)
                    .map { ProjectionDay(it.routineId, it.label, it.routineName) }
                if (days.isEmpty()) {
                    _upcoming.value = UiState.Success(emptyList())
                    return@launch
                }
                val cadence = appPreferences.workoutCadenceDays.first()
                val anchor = sessionDao.getAll()
                    .filter { it.status == "completed" || it.status == "in_progress" }
                    .mapNotNull { s ->
                        runCatching { LocalDate.parse(s.date) }.getOrNull()
                            ?.let { SessionAnchor(it, s.routineId, s.status) }
                    }
                    .maxByOrNull { it.date }

                val slots = WorkoutProjection.project(LocalDate.now(), cadence, anchor, days, count = 4)
                val result = slots.map { slot ->
                    val lifts = slot.routineId
                        ?.let { routineExerciseDao.getByRoutineId(it).take(4) }
                        ?: emptyList()
                    UpcomingWorkout(slot.date, slot.label, slot.routineId, slot.routineName, lifts)
                }
                _upcoming.value = UiState.Success(result)
            } catch (_: Exception) {
                _upcoming.value = UiState.Success(emptyList())
            }
        }
    }

    private fun observeRoutines() {
        viewModelScope.launch {
            routineRepository.routines
                .onStart { _routines.value = UiState.Loading }
                .catch { _routines.value = UiState.Error(it.message ?: "Unknown error") }
                .collect { localRoutines ->
                    _routines.value = UiState.Success(localRoutines)
                    loadRoutineExercises(localRoutines)
                    if (!autoGenerateTriggered && localRoutines.isEmpty()) {
                        val onboardingDone = appPreferences.onboardingDone.first()
                        if (onboardingDone) {
                            autoGenerateTriggered = true
                            generateInitialRoutine()
                        }
                    }
                }
        }
    }

    private fun loadRoutineExercises(routines: List<WorkoutRoutineEntity>) {
        viewModelScope.launch {
            val map = routines.associate { routine ->
                routine.id to routineExerciseDao.getByRoutineId(routine.id).take(4)
            }
            _routineExercises.value = map
        }
    }

    fun sync() {
        viewModelScope.launch {
            try { routineRepository.sync() } catch (_: Exception) {}
            try { sessionRepository.syncPending() } catch (_: Exception) {}
            try { programRepository.sync() } catch (_: Exception) {}
            try { metricRepository.sync() } catch (_: Exception) {}
            _nextProgramDay.value = programRepository.getNextProgramDay()
            loadStats()
            loadUpcoming()
        }
    }

    fun generateInitialRoutine() {
        viewModelScope.launch {
            _generatingPlan.value = true
            try {
                val profile = appPreferences.userProfile.first()
                val response = aiRepository.chat(
                    ChatRequest(
                        messages = listOf(
                            ChatMessage(
                                role = "user",
                                content = "Based on my profile, generate a multi-day workout program for me.",
                            )
                        ),
                        userContext = profile.toContextString().ifBlank { null },
                    )
                )
                val program = response.suggestedProgram
                if (program != null) {
                    // First-run auto-accept is safe — there's no existing active program
                    // to clobber. This gives new users a scheduled program out of the box,
                    // created silently here (never written to chat history).
                    aiRepository.acceptProgram(
                        AcceptProgramRequest(name = program.name, days = program.days)
                    )
                    // Pull the new program AND its per-day routines into the local cache, or
                    // Home keeps showing the empty "ask the coach" prompt (which pushes the
                    // user into the chat) even though a program now exists.
                    runCatching { programRepository.sync() }
                    runCatching { routineRepository.sync() }
                    refresh()
                } else {
                    response.suggestedRoutine?.let { routine ->
                        routineRepository.createRoutine(
                            RoutineCreate(name = routine.name, source = "ai", exercises = routine.exercises)
                        )
                    }
                }
            } catch (_: Exception) {
                // silent — user can still create a routine manually
            } finally {
                _generatingPlan.value = false
            }
        }
    }

    fun startSession(routineId: String) {
        if (_startState.value is UiState.Loading) return
        viewModelScope.launch {
            _startState.value = UiState.Loading
            try {
                val session = sessionRepository.createSession(
                    SessionCreate(routineId = routineId, date = LocalDate.now().toString()),
                )
                _navigateToWorkout.emit(session.id)
            } catch (e: Exception) {
                _startState.value = UiState.Error(e.message ?: "Could not start workout")
                return@launch
            }
            _startState.value = UiState.Idle
        }
    }

    fun deleteRoutine(routineId: String) {
        viewModelScope.launch {
            try {
                routineRepository.deleteRoutine(routineId)
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Could not delete routine"
            }
        }
    }

    fun renameRoutine(routineId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            try {
                routineRepository.renameRoutine(routineId, RoutineUpdate(name = newName.trim()))
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Could not rename routine"
            }
        }
    }

    fun logBodyweight(weight: Double) {
        viewModelScope.launch {
            try {
                metricRepository.addMetric(
                    BodyMetricCreate(date = LocalDate.now().toString(), weight = weight)
                )
            } catch (_: Exception) {}
        }
    }

    fun clearActionError() {
        _actionError.value = null
    }

    private companion object {
        fun greetingForTime(time: LocalTime, firstName: String? = null): String {
            val base = when (time.hour) {
                in 5..11 -> "Good morning"
                in 12..16 -> "Good afternoon"
                else -> "Good evening"
            }
            return if (firstName.isNullOrBlank()) base else "$base, $firstName"
        }

        /** First whitespace-delimited token of a full name, e.g. "Sonic Hedgehog" -> "Sonic". */
        fun firstNameOf(fullName: String): String = fullName.trim().substringBefore(' ').trim()
    }
}
