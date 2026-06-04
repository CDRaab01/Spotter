package com.spotter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.dao.PlannedExerciseDao
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.PlannedExerciseEntity
import com.spotter.data.local.entity.WorkoutPlanEntity
import com.spotter.data.model.AcceptProgramRequest
import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.ChatMessage
import com.spotter.data.model.ChatRequest
import com.spotter.data.model.PlanCreate
import com.spotter.data.model.PlanUpdate
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.ProgramDayOut
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.MetricRepository
import com.spotter.data.repository.PlanRepository
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
    private val planRepository: PlanRepository,
    private val sessionRepository: SessionRepository,
    private val metricRepository: MetricRepository,
    private val aiRepository: AiRepository,
    private val programRepository: ProgramRepository,
    private val appPreferences: AppPreferences,
    private val sessionDao: WorkoutSessionDao,
    private val programDao: WorkoutProgramDao,
    private val programDayDao: ProgramDayDao,
    private val plannedExerciseDao: PlannedExerciseDao,
) : ViewModel() {

    private val _plans = MutableStateFlow<UiState<List<WorkoutPlanEntity>>>(UiState.Loading)
    val plans: StateFlow<UiState<List<WorkoutPlanEntity>>> = _plans

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

    private val _activeProgramId = MutableStateFlow<String?>(null)
    val activeProgramId: StateFlow<String?> = _activeProgramId.asStateFlow()

    private val _nextProgramDay = MutableStateFlow<ProgramDayOut?>(null)
    val nextProgramDay: StateFlow<ProgramDayOut?> = _nextProgramDay.asStateFlow()

    private val _upcoming = MutableStateFlow<UiState<List<UpcomingWorkout>>>(UiState.Loading)
    val upcoming: StateFlow<UiState<List<UpcomingWorkout>>> = _upcoming.asStateFlow()

    private val _greeting = MutableStateFlow(greetingForTime(LocalTime.now()))
    val greeting: StateFlow<String> = _greeting.asStateFlow()

    private val _planExercises = MutableStateFlow<Map<String, List<PlannedExerciseEntity>>>(emptyMap())
    val planExercises: StateFlow<Map<String, List<PlannedExerciseEntity>>> = _planExercises.asStateFlow()

    /** Latest logged bodyweight in pounds, or null when none has been recorded. */
    private val _bodyweight = MutableStateFlow<Double?>(null)
    val bodyweight: StateFlow<Double?> = _bodyweight.asStateFlow()

    private var autoGenerateTriggered = false

    init {
        observePlans()
        observeBodyweight()
        sync()
        loadStats()
        loadUpcoming()
    }

    private fun loadStats() {
        viewModelScope.launch {
            try {
                val sessions = sessionRepository.listSessions()
                val completed = sessions.filter { it.status == "completed" }
                val completedDates = completed
                    .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
                    .toSet()

                // Streak: consecutive days with a completed workout, deduped per day.
                // Anchor at today if trained today, else yesterday (grace day) so the
                // streak persists through the current day until a workout is finished.
                val today = LocalDate.now()
                var day = if (completedDates.contains(today)) today else today.minusDays(1)
                var streak = 0
                while (completedDates.contains(day)) { streak++; day = day.minusDays(1) }
                _streak.value = streak

                // Active minutes: sum of completed-session durations within the current
                // week (Monday → today).
                val weekStart = today.with(DayOfWeek.MONDAY)
                _weeklyActiveMinutes.value = completed
                    .filter { s ->
                        runCatching { LocalDate.parse(s.date) }.getOrNull()
                            ?.let { !it.isBefore(weekStart) && !it.isAfter(today) } ?: false
                    }
                    .sumOf { (it.durationSeconds ?: 0) } / 60
            } catch (_: Exception) {}
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
                    .map { ProjectionDay(it.planId, it.label, it.planName) }
                if (days.isEmpty()) {
                    _upcoming.value = UiState.Success(emptyList())
                    return@launch
                }
                val cadence = appPreferences.workoutCadenceDays.first()
                val anchor = sessionDao.getAll()
                    .filter { it.status == "completed" || it.status == "in_progress" }
                    .mapNotNull { s ->
                        runCatching { LocalDate.parse(s.date) }.getOrNull()
                            ?.let { SessionAnchor(it, s.planId, s.status) }
                    }
                    .maxByOrNull { it.date }

                val slots = WorkoutProjection.project(LocalDate.now(), cadence, anchor, days, count = 4)
                val result = slots.map { slot ->
                    val lifts = slot.planId
                        ?.let { plannedExerciseDao.getByPlanId(it).take(4) }
                        ?: emptyList()
                    UpcomingWorkout(slot.date, slot.label, slot.planId, slot.planName, lifts)
                }
                _upcoming.value = UiState.Success(result)
            } catch (_: Exception) {
                _upcoming.value = UiState.Success(emptyList())
            }
        }
    }

    private fun observePlans() {
        viewModelScope.launch {
            planRepository.plans
                .onStart { _plans.value = UiState.Loading }
                .catch { _plans.value = UiState.Error(it.message ?: "Unknown error") }
                .collect { localPlans ->
                    _plans.value = UiState.Success(localPlans)
                    loadPlanExercises(localPlans)
                    if (!autoGenerateTriggered && localPlans.isEmpty()) {
                        val onboardingDone = appPreferences.onboardingDone.first()
                        if (onboardingDone) {
                            autoGenerateTriggered = true
                            generateInitialPlan()
                        }
                    }
                }
        }
    }

    private fun loadPlanExercises(plans: List<WorkoutPlanEntity>) {
        viewModelScope.launch {
            val map = plans.associate { plan ->
                plan.id to plannedExerciseDao.getByPlanId(plan.id).take(4)
            }
            _planExercises.value = map
        }
    }

    fun sync() {
        viewModelScope.launch {
            try { planRepository.sync() } catch (_: Exception) {}
            try { sessionRepository.syncPending() } catch (_: Exception) {}
            try { programRepository.sync() } catch (_: Exception) {}
            try { metricRepository.sync() } catch (_: Exception) {}
            _nextProgramDay.value = programRepository.getNextProgramDay()
            loadStats()
            loadUpcoming()
        }
    }

    fun generateInitialPlan() {
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
                    // to clobber. This gives new users a scheduled program out of the box.
                    aiRepository.acceptProgram(
                        AcceptProgramRequest(name = program.name, days = program.days)
                    )
                    runCatching { programRepository.sync() }
                    loadUpcoming()
                } else {
                    response.suggestedPlan?.let { plan ->
                        planRepository.createPlan(
                            PlanCreate(name = plan.name, source = "ai", exercises = plan.exercises)
                        )
                    }
                }
            } catch (_: Exception) {
                // silent — user can still create a plan manually
            } finally {
                _generatingPlan.value = false
            }
        }
    }

    fun startSession(planId: String) {
        if (_startState.value is UiState.Loading) return
        viewModelScope.launch {
            _startState.value = UiState.Loading
            try {
                val session = sessionRepository.createSession(
                    SessionCreate(planId = planId, date = LocalDate.now().toString()),
                )
                _navigateToWorkout.emit(session.id)
            } catch (e: Exception) {
                _startState.value = UiState.Error(e.message ?: "Could not start workout")
                return@launch
            }
            _startState.value = UiState.Idle
        }
    }

    fun deletePlan(planId: String) {
        viewModelScope.launch {
            try {
                planRepository.deletePlan(planId)
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Could not delete plan"
            }
        }
    }

    fun renamePlan(planId: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            try {
                planRepository.renamePlan(planId, PlanUpdate(name = newName.trim()))
            } catch (e: Exception) {
                _actionError.value = e.message ?: "Could not rename plan"
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
        fun greetingForTime(time: LocalTime): String = when (time.hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            else -> "Good evening"
        }
    }
}
