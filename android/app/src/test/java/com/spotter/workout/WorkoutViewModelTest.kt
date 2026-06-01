package com.spotter.workout

import com.spotter.data.model.SessionOut
import com.spotter.data.model.SessionUpdate
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.SetLogUpdate
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.workout.WorkoutViewModel
import com.spotter.util.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: SessionRepository
    private lateinit var viewModel: WorkoutViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
        viewModel = WorkoutViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loadSession transitions to Success with returned data`() = runTest(testDispatcher) {
        val session = fakeSession()
        whenever(repository.getSession("s1")).thenReturn(session)

        viewModel.loadSession("s1")
        // Advance less than 1 second so the timer loop hasn't fired yet
        advanceTimeBy(200)

        assertIs<UiState.Success<SessionOut>>(viewModel.session.value)
        assertEquals(session, (viewModel.session.value as UiState.Success).data)
    }

    @Test
    fun `loadSession transitions to Error on exception`() = runTest(testDispatcher) {
        whenever(repository.getSession("s1")).thenThrow(RuntimeException("timeout"))

        viewModel.loadSession("s1")
        advanceTimeBy(200)

        assertIs<UiState.Error>(viewModel.session.value)
        assertEquals("timeout", (viewModel.session.value as UiState.Error).message)
    }

    @Test
    fun `toggleSet calls updateSet with inverted completed flag`() = runTest(testDispatcher) {
        val session = fakeSession()
        val setLog = session.setLogs.first()
        whenever(repository.updateSet(session.id, setLog.id, SetLogUpdate(completed = true)))
            .thenReturn(setLog.copy(completed = true))
        whenever(repository.getSession(session.id)).thenReturn(session)

        viewModel.toggleSet(session.id, setLog)
        advanceTimeBy(200)

        verify(repository).updateSet(session.id, setLog.id, SetLogUpdate(completed = true))
    }

    @Test
    fun `editSet calls updateSet with new reps and weight`() = runTest(testDispatcher) {
        val session = fakeSession()
        val setLog = session.setLogs.first()
        whenever(repository.updateSet(session.id, setLog.id, SetLogUpdate(reps = 10, weight = 150.0)))
            .thenReturn(setLog.copy(reps = 10, weight = 150.0))
        whenever(repository.getSession(session.id)).thenReturn(session)

        viewModel.editSet(session.id, setLog, newReps = 10, newWeight = 150.0)
        advanceTimeBy(200)

        verify(repository).updateSet(session.id, setLog.id, SetLogUpdate(reps = 10, weight = 150.0))
    }

    @Test
    fun `finishSession marks session completed and emits navigateBack`() = runTest(testDispatcher) {
        val session = fakeSession()
        whenever(repository.updateSession(any(), any()))
            .thenReturn(session.copy(status = "completed"))

        val navEvents = mutableListOf<Unit>()
        val job = launch { viewModel.navigateBack.collect { navEvents.add(it) } }

        viewModel.finishSession(session.id)
        advanceTimeBy(200)

        assertEquals(1, navEvents.size)
        verify(repository).updateSession(
            session.id,
            SessionUpdate(status = "completed", durationSeconds = 0),
        )
        job.cancel()
    }

    @Test
    fun `elapsedSeconds increments every second`() = runTest(testDispatcher) {
        assertEquals(0, viewModel.elapsedSeconds.value)
        advanceTimeBy(3000)
        assertEquals(3, viewModel.elapsedSeconds.value)
    }

    private fun fakeSession(id: String = "session-1") = SessionOut(
        id = id,
        userId = "user-1",
        planId = null,
        date = "2025-06-01",
        status = "in_progress",
        durationSeconds = null,
        note = null,
        setLogs = listOf(
            SetLogOut(
                id = "set-1",
                sessionId = id,
                exerciseId = "exercise-1",
                setNumber = 1,
                reps = 8,
                weight = 135.0,
                completed = false,
            )
        ),
    )
}
