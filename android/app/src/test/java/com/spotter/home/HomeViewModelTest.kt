package com.spotter.home

import com.spotter.data.local.dao.RoutineExerciseDao
import com.spotter.data.local.dao.CardioSessionDao
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.local.entity.ProgramDayEntity
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.local.entity.WorkoutSessionEntity
import com.spotter.data.model.BodyMetricCreate
import com.spotter.data.model.BodyMetricOut
import com.spotter.data.model.RoutineOut
import com.spotter.data.model.SessionSummary
import com.spotter.data.model.RoutineUpdate
import com.spotter.data.model.ProgramDayOut
import com.spotter.data.model.UserOut
import com.spotter.data.remote.ApiService
import com.spotter.data.repository.AiRepository
import com.spotter.data.repository.MetricRepository
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.ProgramRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.home.HomeViewModel
import com.spotter.util.AppPreferences
import com.spotter.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var routineRepository: RoutineRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var metricRepository: MetricRepository
    private lateinit var aiRepository: AiRepository
    private lateinit var programRepository: ProgramRepository
    private lateinit var appPreferences: AppPreferences
    private lateinit var sessionDao: WorkoutSessionDao
    private lateinit var programDao: WorkoutProgramDao
    private lateinit var programDayDao: ProgramDayDao
    private lateinit var routineExerciseDao: RoutineExerciseDao
    private lateinit var cardioSessionDao: CardioSessionDao
    private lateinit var apiService: ApiService
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        routineRepository = mock()
        sessionRepository = mock()
        metricRepository = mock()
        aiRepository = mock()
        programRepository = mock()
        appPreferences = mock()
        sessionDao = mock()
        programDao = mock()
        programDayDao = mock()
        routineExerciseDao = mock()
        cardioSessionDao = mock()
        apiService = mock()
        wheneverBlocking { cardioSessionDao.getCompleted() }.thenReturn(emptyList())
        whenever(routineRepository.routines).thenReturn(emptyFlow())
        whenever(programDao.getAll()).thenReturn(emptyFlow())
        whenever(programDayDao.observeAll()).thenReturn(emptyFlow())
        whenever(appPreferences.onboardingDone).thenReturn(flowOf(false))
        whenever(appPreferences.workoutCadenceDays).thenReturn(flowOf(2))
        whenever(metricRepository.metrics).thenReturn(emptyFlow())
        wheneverBlocking { apiService.getMe() }.thenReturn(
            UserOut(id = "user-1", name = "Sonic Hedgehog", email = "sonic@spotter.com"),
        )
        viewModel = createViewModel()
    }

    private fun createViewModel() = HomeViewModel(
        routineRepository,
        sessionRepository,
        metricRepository,
        aiRepository,
        programRepository,
        appPreferences,
        apiService,
        sessionDao,
        programDao,
        programDayDao,
        routineExerciseDao,
        cardioSessionDao,
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `deleteRoutine delegates to repository`() = runTest(testDispatcher) {
        viewModel.deleteRoutine("routine-1")
        advanceTimeBy(200)

        verify(routineRepository).deleteRoutine("routine-1")
    }

    @Test
    fun `renameRoutine delegates to repository with trimmed name`() = runTest(testDispatcher) {
        whenever(routineRepository.renameRoutine(any(), any())).thenReturn(
            RoutineOut(
                id = "routine-1",
                userId = "user-1",
                name = "New Name",
                source = "manual",
                createdAt = "2026-06-01T00:00:00Z",
            )
        )

        viewModel.renameRoutine("routine-1", "  New Name  ")
        advanceTimeBy(200)

        verify(routineRepository).renameRoutine("routine-1", RoutineUpdate(name = "New Name"))
    }

    @Test
    fun `renameRoutine ignores blank names`() = runTest(testDispatcher) {
        viewModel.renameRoutine("routine-1", "   ")
        advanceTimeBy(200)

        verify(routineRepository, never()).renameRoutine(any(), any())
    }

    @Test
    fun `logBodyweight delegates to metricRepository`() = runTest(testDispatcher) {
        whenever(metricRepository.addMetric(any())).thenReturn(
            BodyMetricOut(id = "m1", userId = "u1", date = "2026-06-01", weight = 185.0)
        )

        viewModel.logBodyweight(185.0)
        advanceTimeBy(200)

        verify(metricRepository).addMetric(any<BodyMetricCreate>())
    }

    @Test
    fun `nextProgramDay is null initially`() {
        assertNull(viewModel.nextProgramDay.value)
    }

    @Test
    fun `sync populates nextProgramDay when active program has a next day`() = runTest(testDispatcher) {
        val nextDay = ProgramDayOut(id = "day-1", label = "Push", order = 0)
        whenever(programRepository.getNextProgramDay()).thenReturn(nextDay)

        viewModel.sync()
        advanceTimeBy(200)

        assertEquals("Push", viewModel.nextProgramDay.value?.label)
    }

    @Test
    fun `sync leaves nextProgramDay null when no active program`() = runTest(testDispatcher) {
        whenever(programRepository.getNextProgramDay()).thenReturn(null)

        viewModel.sync()
        advanceTimeBy(200)

        assertNull(viewModel.nextProgramDay.value)
    }

    @Test
    fun `sync delegates to programRepository`() = runTest(testDispatcher) {
        viewModel.sync()
        advanceTimeBy(200)
        // sync() is also called from init, so verify at least one call total
        verify(programRepository, atLeast(1)).sync()
    }

    @Test
    fun `upcoming is empty when there is no active program`() = runTest(testDispatcher) {
        whenever(programDao.getActive()).thenReturn(null)

        viewModel = createViewModel()
        advanceTimeBy(200)

        val state = viewModel.upcoming.value
        assertIs<UiState.Success<List<*>>>(state)
        assertTrue(state.data.isEmpty())
    }

    @Test
    fun `upcoming projects workouts with limited lifts when a program is active`() = runTest(testDispatcher) {
        whenever(programDao.getActive()).thenReturn(WorkoutProgramEntity("prog-1", "PPL", isActive = true))
        whenever(programDayDao.getByProgram(any())).thenReturn(
            listOf(
                ProgramDayEntity("d1", "prog-1", "routine-A", "Push", 0, "Push"),
                ProgramDayEntity("d2", "prog-1", "routine-B", "Pull", 1, "Pull"),
            )
        )
        whenever(sessionDao.getAll()).thenReturn(
            listOf(
                WorkoutSessionEntity(
                    id = "s1", userId = "u1", routineId = "routine-A",
                    date = LocalDate.now().toString(), status = "completed",
                    durationSeconds = null, note = null,
                )
            )
        )
        whenever(routineExerciseDao.getByRoutineId(any())).thenReturn(
            (1..6).map {
                RoutineExerciseEntity("routine-B", "ex-$it", "Lift $it", 3, 8, 100.0, false, it)
            }
        )

        viewModel = createViewModel()
        advanceTimeBy(200)

        val state = viewModel.upcoming.value
        assertIs<UiState.Success<List<com.spotter.util.UpcomingWorkout>>>(state)
        assertEquals(4, state.data.size)
        // Completed Push -> next slot is Pull, capped at 4 lifts.
        assertEquals("routine-B", state.data[0].routineId)
        assertEquals(4, state.data[0].lifts.size)
        // activeProgramId is surfaced for the tappable upcoming block.
        assertEquals("prog-1", viewModel.activeProgramId.value)
    }

    @Test
    fun `loadStats sums active minutes and dedupes same-day streak`() = runTest(testDispatcher) {
        val today = LocalDate.now().toString()
        whenever(sessionRepository.listSessions()).thenReturn(
            listOf(
                SessionSummary(id = "a", date = today, status = "completed", durationSeconds = 1800, totalSets = 5, completedSets = 5),
                SessionSummary(id = "b", date = today, status = "completed", durationSeconds = 1200, totalSets = 4, completedSets = 4),
                SessionSummary(id = "c", date = today, status = "in_progress", durationSeconds = 9999, totalSets = 3, completedSets = 0),
            )
        )

        viewModel = createViewModel()
        advanceTimeBy(200)

        // Two completed sessions today → 3000s = 50 min; in-progress ignored.
        assertEquals(50, viewModel.weeklyActiveMinutes.value)
        // Two completed sessions on the same day count once.
        assertEquals(1, viewModel.streak.value)
    }

    @Test
    fun `loadStats folds completed cardio minutes into active minutes`() = runTest(testDispatcher) {
        val today = LocalDate.now().toString()
        whenever(sessionRepository.listSessions()).thenReturn(
            listOf(
                SessionSummary(id = "a", date = today, status = "completed", durationSeconds = 1800, totalSets = 5, completedSets = 5),
            )
        )
        wheneverBlocking { cardioSessionDao.getCompleted() }.thenReturn(
            listOf(
                CardioSessionEntity(
                    id = "c1", serverId = "c1", programId = "c25k", weekNumber = 1, dayNumber = 1,
                    startedAt = Instant.now().toString(), completedAt = Instant.now().toString(),
                    status = "completed", totalElapsedSec = 600,
                ),
            )
        )

        viewModel = createViewModel()
        advanceTimeBy(200)

        // 1800s strength + 600s cardio = 2400s = 40 min.
        assertEquals(40, viewModel.weeklyActiveMinutes.value)
    }

    @Test
    fun `streak counts yesterday as grace day when today not trained`() = runTest(testDispatcher) {
        val yesterday = LocalDate.now().minusDays(1).toString()
        whenever(sessionRepository.listSessions()).thenReturn(
            listOf(
                SessionSummary(id = "a", date = yesterday, status = "completed", durationSeconds = 600, totalSets = 3, completedSets = 3),
            )
        )

        viewModel = createViewModel()
        advanceTimeBy(200)

        assertEquals(1, viewModel.streak.value)
    }

    @Test
    fun `streak is not broken by a scheduled rest day between two workout days`() = runTest(testDispatcher) {
        // Mon=Push(completed), Tue=Rest(scheduled), Wed=Pull(completed), Thu=today(no session).
        // Old logic: streak=1 (stops at Tue gap). New logic: streak=2 (skips Tue rest day).
        val mon = LocalDate.now().minusDays(3).toString()
        val wed = LocalDate.now().minusDays(1).toString()
        whenever(sessionRepository.listSessions()).thenReturn(
            listOf(
                SessionSummary(id = "s1", date = mon, status = "completed", durationSeconds = 3600, totalSets = 5, completedSets = 5),
                SessionSummary(id = "s2", date = wed, status = "completed", durationSeconds = 3600, totalSets = 5, completedSets = 5),
            )
        )
        val program = WorkoutProgramEntity("prog-1", "Push-Rest-Pull", isActive = true)
        whenever(programDao.getActive()).thenReturn(program)
        // Cycle: Push(0), Rest(1), Pull(2) — anchor on Mon=Push → Tue=Rest, Wed=Pull.
        whenever(programDayDao.getByProgram("prog-1")).thenReturn(
            listOf(
                ProgramDayEntity("d1", "prog-1", "r-push", "Push", 0, "Push"),
                ProgramDayEntity("d2", "prog-1", null, "Rest", 1, null),
                ProgramDayEntity("d3", "prog-1", "r-pull", "Pull", 2, "Pull"),
            )
        )
        // Anchor used by computeRestDayDates — most recent completed session is Wed/Pull.
        whenever(sessionDao.getAll()).thenReturn(
            listOf(
                WorkoutSessionEntity("s1", "u1", "r-push", mon, "completed", null, null),
                WorkoutSessionEntity("s2", "u1", "r-pull", wed, "completed", null, null),
            )
        )
        whenever(routineExerciseDao.getByRoutineId(any())).thenReturn(emptyList())

        viewModel = createViewModel()
        advanceTimeBy(500)

        assertEquals(2, viewModel.streak.value)
    }

    @Test
    fun `rest days today and yesterday do not reset grace day anchor past last workout`() = runTest(testDispatcher) {
        // Mon=Push(completed), Tue=Rest, Wed=Rest(today, no session).
        // The streak chain Mon→Tue(rest)→Wed(rest, today) is intact → streak=1.
        // Old logic: grace day = Tue, Tue has no session → streak=0.
        val mon = LocalDate.now().minusDays(2).toString()
        whenever(sessionRepository.listSessions()).thenReturn(
            listOf(SessionSummary(id = "s1", date = mon, status = "completed", durationSeconds = 3600, totalSets = 5, completedSets = 5))
        )
        val program = WorkoutProgramEntity("prog-1", "Test", isActive = true)
        whenever(programDao.getActive()).thenReturn(program)
        // Cycle: Push(0), Rest(1), Rest(2) — Mon=Push, Tue=Rest, Wed=Rest(today).
        whenever(programDayDao.getByProgram("prog-1")).thenReturn(
            listOf(
                ProgramDayEntity("d1", "prog-1", "r-push", "Push", 0, "Push"),
                ProgramDayEntity("d2", "prog-1", null, "Rest", 1, null),
                ProgramDayEntity("d3", "prog-1", null, "Rest", 2, null),
            )
        )
        whenever(sessionDao.getAll()).thenReturn(
            listOf(WorkoutSessionEntity("s1", "u1", "r-push", mon, "completed", null, null))
        )
        whenever(routineExerciseDao.getByRoutineId(any())).thenReturn(emptyList())

        viewModel = createViewModel()
        advanceTimeBy(500)

        assertEquals(1, viewModel.streak.value)
    }

    @Test
    fun `greeting is non-blank`() {
        assertTrue(viewModel.greeting.value.isNotBlank())
    }

    @Test
    fun `greeting appends the user's first name`() = runTest(testDispatcher) {
        advanceTimeBy(200)

        val greeting = viewModel.greeting.value
        assertTrue(greeting.endsWith(", Sonic"), "expected first name suffix, got: $greeting")
    }
}
