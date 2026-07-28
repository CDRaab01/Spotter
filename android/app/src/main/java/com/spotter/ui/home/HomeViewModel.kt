package com.spotter.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.dao.CardioSessionDao
import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.local.entity.WorkoutRoutineEntity
import com.spotter.data.model.AcceptProgramRequest
import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.ChatMessage
import com.spotter.data.model.ChatRequest
import com.spotter.data.model.InsightsOut
import com.spotter.data.model.RoutineCreate
import com.spotter.data.model.RoutineUpdate
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.ProgramDayOut
import com.spotter.data.remote.ApiService
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.MetricRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.cardio.CardioFormat
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import java.io.IOException
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
    private val exerciseRepository: ExerciseRepository,
    private val appPreferences: AppPreferences,
    private val api: ApiService,
    private val sessionDao: WorkoutSessionDao,
    private val programDao: WorkoutProgramDao,
    private val programDayDao: ProgramDayDao,
    private val routineExerciseDao: RoutineExerciseDao,
    private val cardioSessionDao: CardioSessionDao,
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

    /** All cached programs (active first surfaces naturally on Home's "Your programs" section). */
    private val _programs = MutableStateFlow<List<WorkoutProgramEntity>>(emptyList())
    val programs: StateFlow<List<WorkoutProgramEntity>> = _programs.asStateFlow()

    /** programId → number of days, for the program card subtitle. */
    private val _programDayCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val programDayCounts: StateFlow<Map<String, Int>> = _programDayCounts.asStateFlow()

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

    /**
     * Non-null when the last sync round couldn't reach the server (connectivity, not an HTTP
     * error) and the screen is therefore serving cached data: the value is the timestamp of the
     * last successful sync, rendered by the Home stale banner. Null while fresh — or when the
     * device has never synced (nothing honest to date the cache with).
     */
    private val _staleAsOfMs = MutableStateFlow<Long?>(null)
    val staleAsOfMs: StateFlow<Long?> = _staleAsOfMs.asStateFlow()

    /**
     * Routines not attached to any program day. Surfaced as Home's "Your routines" section so a
     * manually created routine is reachable before (or without) being scheduled into a program —
     * previously a saved routine had no screen that could open it until a program day linked it.
     */
    private val _standaloneRoutines = MutableStateFlow<List<WorkoutRoutineEntity>>(emptyList())
    val standaloneRoutines: StateFlow<List<WorkoutRoutineEntity>> = _standaloneRoutines.asStateFlow()

    /**
     * Proactive coaching signals (stalled lifts + PRs this week) from GET /insights. Null until
     * a round succeeds — and a failed round leaves the last good value in place rather than
     * blanking it. Purely additive polish: no error is ever surfaced for this.
     */
    private val _insights = MutableStateFlow<InsightsOut?>(null)
    val insights: StateFlow<InsightsOut?> = _insights.asStateFlow()

    private var autoGenerateTriggered = false

    init {
        observeRoutines()
        observeStandaloneRoutines()
        observePrograms()
        observeBodyweight()
        sync()
        loadStats()
        loadUpcoming()
        loadGreeting()
        loadInsights()
    }

    /**
     * Best-effort: any failure (offline, 404 on an older server, HTTP error) simply means no
     * coach-signals card. Home must look exactly as it did before when this doesn't land.
     */
    private fun loadInsights() {
        viewModelScope.launch {
            runCatching { aiRepository.insights() }
                .onSuccess { _insights.value = it }
        }
    }

    private fun observePrograms() {
        viewModelScope.launch {
            // Combine both tables: during a sync the programs land before their days, so counts
            // must re-derive whenever EITHER table changes or they'd freeze at "no days yet".
            combine(programDao.getAll(), programDayDao.observeAll()) { programs, days ->
                programs.sortedByDescending { it.isActive } to
                    days.groupingBy { it.programId }.eachCount()
            }.collect { (programs, counts) ->
                _programs.value = programs
                _programDayCounts.value = counts
            }
        }
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
                // Completed cardio counts exactly like a completed strength session: a manual walk/
                // run, a guided C25K day, and a Free Run all feed the streak + active-minutes stats.
                // Each cardio (date, durationSec) pair joins the strength ones below.
                val cardioCompleted = runCatching {
                    cardioSessionDao.observeAll().first()
                        .filter { it.status == "completed" }
                        .mapNotNull { s ->
                            CardioFormat.parseDate(s.completedAt)?.let { it to s.totalElapsedSec }
                        }
                }.getOrDefault(emptyList())

                val completedDates = (
                    completed.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() } +
                        cardioCompleted.map { it.first }
                    ).toSet()

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
                val strengthThisWeek = completed.mapNotNull { s ->
                    val date = runCatching { LocalDate.parse(s.date) }.getOrNull()
                        ?: return@mapNotNull null
                    if (!date.isBefore(weekStart) && !date.isAfter(today)) {
                        date to (s.durationSeconds ?: 0)
                    } else {
                        null
                    }
                }
                // Completed cardio contributes its elapsed time to active minutes too.
                val cardioThisWeek = cardioCompleted.filter { (date, _) ->
                    !date.isBefore(weekStart) && !date.isAfter(today)
                }
                val thisWeek = strengthThisWeek + cardioThisWeek
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

    /** Re-pull stats, upcoming workouts, and coach signals (called on Home resume). */
    fun refresh() {
        loadStats()
        loadUpcoming()
        loadInsights()
    }

    /**
     * Retry hook for the full-screen error state: the routines flow dies once its `catch`
     * fires, so recovering needs a fresh collection plus a sync round.
     */
    fun retryLoad() {
        observeRoutines()
        sync()
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
                val today = LocalDate.now()
                // Strength program projection (empty when there's no active strength program).
                val active = programDao.getActive()
                _activeProgramId.value = active?.id
                val strength = active?.let { program ->
                    val days = programDayDao.getByProgram(program.id)
                        .map { ProjectionDay(it.routineId, it.label, it.routineName) }
                    if (days.isEmpty()) {
                        emptyList()
                    } else {
                        val cadence = appPreferences.workoutCadenceDays.first()
                        val anchor = sessionDao.getAll()
                            .filter { it.status == "completed" || it.status == "in_progress" }
                            .mapNotNull { s ->
                                runCatching { LocalDate.parse(s.date) }.getOrNull()
                                    ?.let { SessionAnchor(it, s.routineId, s.status) }
                            }
                            .maxByOrNull { it.date }
                        WorkoutProjection.project(today, cadence, anchor, days, count = 4).map { slot ->
                            val lifts = slot.routineId
                                ?.let { routineExerciseDao.getByRoutineId(it).take(4) }
                                ?: emptyList()
                            UpcomingWorkout(slot.date, slot.label, slot.routineId, slot.routineName, lifts, slot.dayIndex)
                        }
                    }
                }.orEmpty()

                // Cardio program projection — independent of the strength program, so a user can
                // have both scheduled at once.
                val cardio = loadUpcomingCardio(today)

                // Merge and keep the four soonest across both, so an accepted cardio program shows
                // up in Upcoming next to (or instead of) strength days.
                val merged = (strength + cardio).sortedBy { it.date }.take(4)
                _upcoming.value = UiState.Success(merged)
            } catch (_: Exception) {
                _upcoming.value = UiState.Success(emptyList())
            }
        }
    }

    private suspend fun loadUpcomingCardio(today: LocalDate): List<UpcomingWorkout> {
        val cardioId = appPreferences.activeCardioProgramId.first() ?: return emptyList()
        val program = CardioPrograms.byId(cardioId)?.takeIf { it.weeks != null } ?: return emptyList()
        val sessions = cardioSessionDao.observeByProgram(cardioId).first()
        return CardioSchedule.upcoming(program, sessions, today, count = 4)
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

    private fun observeStandaloneRoutines() {
        viewModelScope.launch {
            combine(routineRepository.routines, programDayDao.observeAll()) { routines, days ->
                val linked = days.mapNotNull { it.routineId }.toSet()
                routines.filter { it.id !in linked }
            }.collect { _standaloneRoutines.value = it }
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
            // The routine pull doubles as the freshness probe: unlike the other repos (which
            // swallow network failures internally), RoutineRepository.sync() lets the pull's
            // exception escape, so its outcome distinguishes "synced" from "offline".
            val probe = runCatching { routineRepository.sync() }
            try { sessionRepository.syncPending() } catch (_: Exception) {}
            try { programRepository.sync() } catch (_: Exception) {}
            try { metricRepository.sync() } catch (_: Exception) {}
            // Opportunistic exercise-catalog seed so offline library search, preset resolution,
            // and the offline muscle-group summary have data. Best-effort — failures are silent.
            exerciseRepository.refreshCatalog()
            when (probe.exceptionOrNull()) {
                null -> {
                    appPreferences.setLastSuccessfulSyncMs(System.currentTimeMillis())
                    _staleAsOfMs.value = null
                }
                // Offline: date the on-screen data by the last successful sync (null before the
                // first ever sync → no banner, there's nothing honest to date the cache with).
                is IOException -> _staleAsOfMs.value = appPreferences.lastSuccessfulSyncMs.first()
                // Anything else (e.g. retrofit2.HttpException): the server was reachable, so the
                // data isn't "offline stale" — errors keep surfacing through the normal paths.
                else -> _staleAsOfMs.value = null
            }
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
                // The coach being down shouldn't read as a dead app: point at the preset
                // path, which works entirely without the LLM.
                _actionError.value =
                    "Couldn't reach the AI coach — browse preset programs to get started."
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
                // Surface via the snackbar — startState is only read for its Loading flag,
                // so an Error left there was invisible and the button silently did nothing.
                _actionError.value = "Couldn't start the workout. Check your connection and try again."
                _startState.value = UiState.Idle
                return@launch
            }
            _startState.value = UiState.Idle
        }
    }

    /**
     * "Start workout" launcher shortcut: resume today's in-progress session if there is one,
     * otherwise start the soonest scheduled routine. Emits onto [navigateToWorkout] (resume) or
     * defers to [startSession] (which emits after the session is created). Surfaces a hint when
     * nothing is scheduled rather than starting an arbitrary workout.
     */
    fun startTodaysWorkout() {
        viewModelScope.launch {
            val today = LocalDate.now().toString()
            val active = sessionDao.getAll()
                .firstOrNull { it.status == "in_progress" && it.date == today }
            if (active != null) {
                _navigateToWorkout.emit(active.id)
                return@launch
            }
            // Wait for the upcoming projection to resolve (it always emits a Success, even empty).
            val slots = upcoming
                .filterIsInstance<UiState.Success<List<UpcomingWorkout>>>()
                .first()
                .data
            val routineId = slots.firstOrNull { it.routineId != null }?.routineId
            if (routineId != null) {
                startSession(routineId)
            } else {
                _actionError.value = "No workout scheduled yet — ask your Coach to build one."
            }
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
            } catch (_: Exception) {
                _actionError.value = "Couldn't save your weight. Try again."
            }
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
