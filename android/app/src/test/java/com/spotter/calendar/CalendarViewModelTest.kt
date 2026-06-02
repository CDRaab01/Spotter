package com.spotter.calendar

import com.spotter.data.local.dao.PlannedExerciseDao
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.model.SessionOut
import com.spotter.data.repository.CalendarRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.calendar.CalendarViewModel
import com.spotter.util.AppPreferences
import com.spotter.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var appPreferences: AppPreferences
    private lateinit var sessionDao: WorkoutSessionDao
    private lateinit var programDao: WorkoutProgramDao
    private lateinit var programDayDao: ProgramDayDao
    private lateinit var plannedExerciseDao: PlannedExerciseDao

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        calendarRepository = mock()
        sessionRepository = mock()
        appPreferences = mock()
        sessionDao = mock()
        programDao = mock()
        programDayDao = mock()
        plannedExerciseDao = mock()
        whenever(appPreferences.workoutCadenceDays).thenReturn(flowOf(2))
    }

    private fun createViewModel() = CalendarViewModel(
        calendarRepository,
        sessionRepository,
        appPreferences,
        sessionDao,
        programDao,
        programDayDao,
        plannedExerciseDao,
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadMonth loads entries and leaves projections empty without an active program`() =
        runTest(testDispatcher) {
            whenever(calendarRepository.getCalendar(any(), any())).thenReturn(emptyList())
            whenever(programDao.getActive()).thenReturn(null)

            val viewModel = createViewModel()
            advanceTimeBy(200)

            assertIs<UiState.Success<*>>(viewModel.entries.value)
            assertTrue(viewModel.projected.value.isEmpty())
        }

    @Test
    fun `startProjectedSession creates a session and emits navigation`() = runTest(testDispatcher) {
        whenever(calendarRepository.getCalendar(any(), any())).thenReturn(emptyList())
        whenever(programDao.getActive()).thenReturn(null)
        whenever(sessionRepository.createSession(any())).thenReturn(
            SessionOut(id = "sess-1", userId = "u1", date = "2026-06-02", status = "in_progress"),
        )

        val viewModel = createViewModel()
        advanceTimeBy(200)

        val events = mutableListOf<String>()
        val job = launch { viewModel.navigateToWorkout.collect { events.add(it) } }

        viewModel.startProjectedSession("plan-A")
        advanceTimeBy(200)

        verify(sessionRepository).createSession(any())
        assertEquals(listOf("sess-1"), events)
        job.cancel()
    }
}
