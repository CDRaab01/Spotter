package com.spotter.history

import com.spotter.data.model.RoutineCreate
import com.spotter.data.model.RoutineOut
import com.spotter.data.model.SessionCreate
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SetLogOut
import com.spotter.data.repository.RoutineRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.history.SessionDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var sessionRepository: SessionRepository
    private lateinit var routineRepository: RoutineRepository
    private lateinit var viewModel: SessionDetailViewModel

    private val logs = listOf(
        SetLogOut(
            id = "l1", sessionId = "s1", exerciseId = "bench", setNumber = 1,
            reps = 8, weight = 115.0, completed = true,
        ),
        SetLogOut(
            id = "l2", sessionId = "s1", exerciseId = "bench", setNumber = 2,
            reps = 8, weight = 115.0, completed = true,
        ),
    )

    private val session = SessionOut(
        id = "s1", userId = "u1", routineId = "server-routine", routineName = "Push Day",
        date = "2026-07-28", status = "completed", setLogs = logs,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        sessionRepository = mock()
        routineRepository = mock()
        viewModel = SessionDetailViewModel(sessionRepository, routineRepository)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.loadSession() {
        whenever(sessionRepository.getSession("s1")).thenReturn(session)
        viewModel.load("s1")
        advanceTimeBy(200)
    }

    @Test
    fun `repeat creates today's session on the local routine id and emits its id`() =
        runTest(testDispatcher) {
            loadSession()
            whenever(routineRepository.localRoutineId("server-routine")).thenReturn("local-routine")
            whenever(sessionRepository.createSession(any()))
                .thenReturn(session.copy(id = "new-local", status = "in_progress"))

            val started = mutableListOf<String>()
            val job = launch { viewModel.startedSessionId.collect { started.add(it) } }

            viewModel.repeatWorkout()
            advanceTimeBy(200)

            val captor = argumentCaptor<SessionCreate>()
            verify(sessionRepository).createSession(captor.capture())
            assertEquals("local-routine", captor.firstValue.routineId)
            assertEquals(listOf("new-local"), started)
            job.cancel()
        }

    @Test
    fun `repeat surfaces a failure instead of navigating`() = runTest(testDispatcher) {
        loadSession()
        whenever(routineRepository.localRoutineId("server-routine")).thenReturn("local-routine")
        whenever(sessionRepository.createSession(any())).thenThrow(RuntimeException("offline"))

        val started = mutableListOf<String>()
        val job = launch { viewModel.startedSessionId.collect { started.add(it) } }

        viewModel.repeatWorkout()
        advanceTimeBy(200)

        assertTrue(started.isEmpty())
        assertEquals("offline", viewModel.actionMessage.value)
        job.cancel()
    }

    @Test
    fun `repeat is unavailable for an ad-hoc session with no routine`() = runTest(testDispatcher) {
        whenever(sessionRepository.getSession("s1")).thenReturn(session.copy(routineId = null))
        viewModel.load("s1")
        advanceTimeBy(200)

        viewModel.repeatWorkout()
        advanceTimeBy(200)

        verify(sessionRepository, never()).createSession(any())
    }

    @Test
    fun `save as routine creates a routine from the performed sets`() = runTest(testDispatcher) {
        loadSession()
        whenever(routineRepository.createRoutine(any())).thenReturn(
            RoutineOut(
                id = "r2", userId = "u1", name = "Push Day (copy)",
                source = "manual", createdAt = "2026-07-28T00:00:00Z",
            )
        )

        viewModel.saveAsRoutine()
        advanceTimeBy(200)

        val captor = argumentCaptor<RoutineCreate>()
        verify(routineRepository).createRoutine(captor.capture())
        val req = captor.firstValue
        assertEquals("Push Day (copy)", req.name)
        assertEquals(1, req.exercises.size)
        assertEquals(2, req.exercises[0].targetSets)
        assertEquals(115.0, req.exercises[0].targetWeight)
        assertNotNull(viewModel.actionMessage.value)
    }

    @Test
    fun `save as routine refuses a session with no completed sets`() = runTest(testDispatcher) {
        whenever(sessionRepository.getSession("s1"))
            .thenReturn(session.copy(setLogs = logs.map { it.copy(completed = false) }))
        viewModel.load("s1")
        advanceTimeBy(200)

        viewModel.saveAsRoutine()
        advanceTimeBy(200)

        verify(routineRepository, never()).createRoutine(any())
        assertEquals("No completed sets to save as a routine.", viewModel.actionMessage.value)
    }
}
