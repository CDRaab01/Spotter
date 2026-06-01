package com.spotter.workout

import com.spotter.data.model.ExercisePrior
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        whenever(repository.getPriorBests("s1")).thenReturn(emptyList())

        viewModel.loadSession("s1")
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
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())

        viewModel.toggleSet(session.id, setLog)
        advanceTimeBy(200)

        verify(repository).updateSet(session.id, setLog.id, SetLogUpdate(completed = true))
    }

    @Test
    fun `toggleSet to completed starts rest timer`() = runTest(testDispatcher) {
        val session = fakeSession()
        val setLog = session.setLogs.first() // targetReps = 8 → 90s timer
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(completed = true))
        whenever(repository.getSession(any())).thenReturn(session)
        whenever(repository.getPriorBests(any())).thenReturn(emptyList())

        viewModel.toggleSet(session.id, setLog)
        advanceTimeBy(200)

        assertNotNull(viewModel.restTimerSeconds.value)
        assertEquals(90, viewModel.restTimerSeconds.value)
    }

    @Test
    fun `toggleSet to uncompleted does not start rest timer`() = runTest(testDispatcher) {
        val session = fakeSession()
        val completedSet = session.setLogs.first().copy(completed = true)
        whenever(repository.updateSet(any(), any(), any())).thenReturn(completedSet.copy(completed = false))
        whenever(repository.getSession(any())).thenReturn(session)
        whenever(repository.getPriorBests(any())).thenReturn(emptyList())

        viewModel.toggleSet(session.id, completedSet)
        advanceTimeBy(200)

        assertNull(viewModel.restTimerSeconds.value)
    }

    @Test
    fun `dismissRestTimer clears timer`() = runTest(testDispatcher) {
        viewModel.startRestTimer(8)
        advanceTimeBy(200)
        assertNotNull(viewModel.restTimerSeconds.value)

        viewModel.dismissRestTimer()

        assertNull(viewModel.restTimerSeconds.value)
    }

    @Test
    fun `rest timer uses 180s for strength reps (le 5)`() = runTest(testDispatcher) {
        viewModel.startRestTimer(5)
        advanceTimeBy(200)
        assertEquals(180, viewModel.restTimerSeconds.value)
    }

    @Test
    fun `rest timer uses 60s for conditioning reps (gt 12)`() = runTest(testDispatcher) {
        viewModel.startRestTimer(15)
        advanceTimeBy(200)
        assertEquals(60, viewModel.restTimerSeconds.value)
    }

    @Test
    fun `editSet calls updateSet with new reps and weight`() = runTest(testDispatcher) {
        val session = fakeSession()
        val setLog = session.setLogs.first()
        whenever(repository.updateSet(session.id, setLog.id, SetLogUpdate(reps = 10, weight = 150.0)))
            .thenReturn(setLog.copy(reps = 10, weight = 150.0))
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())

        viewModel.editSet(session.id, setLog, newReps = 10, newWeight = 150.0)
        advanceTimeBy(200)

        verify(repository).updateSet(session.id, setLog.id, SetLogUpdate(reps = 10, weight = 150.0))
    }

    @Test
    fun `finishSession emits navigateToSummary`() = runTest(testDispatcher) {
        val session = fakeSession()
        whenever(repository.getSession("s1")).thenReturn(session)
        whenever(repository.getPriorBests("s1")).thenReturn(emptyList())
        whenever(repository.updateSession(any(), any()))
            .thenReturn(session.copy(status = "completed"))

        viewModel.loadSession("s1")
        advanceTimeBy(200)

        val summaryEvents = mutableListOf<com.spotter.ui.workout.WorkoutSummaryData>()
        val job = launch { viewModel.navigateToSummary.collect { summaryEvents.add(it) } }

        viewModel.finishSession(session.id)
        advanceTimeBy(200)

        assertEquals(1, summaryEvents.size)
        assertEquals(0, summaryEvents[0].doneSets) // fakeSession has no completed sets
        assertEquals(1, summaryEvents[0].totalSets)
        job.cancel()
    }

    @Test
    fun `saveExerciseNote updates notes and calls repository`() = runTest(testDispatcher) {
        val session = fakeSession()
        whenever(repository.updateSession(any(), any())).thenReturn(session)

        viewModel.saveExerciseNote(session.id, "exercise-1", "Keep elbows in")
        advanceTimeBy(200)

        assertEquals("Keep elbows in", viewModel.exerciseNotes.value["exercise-1"])
        verify(repository).updateSession(
            session.id,
            SessionUpdate(exerciseNotes = mapOf("exercise-1" to "Keep elbows in")),
        )
    }

    @Test
    fun `loadSession populates exercise notes from session data`() = runTest(testDispatcher) {
        val session = fakeSession().copy(
            exerciseNotes = mapOf("exercise-1" to "Full ROM")
        )
        whenever(repository.getSession("s1")).thenReturn(session)
        whenever(repository.getPriorBests("s1")).thenReturn(emptyList())

        viewModel.loadSession("s1")
        advanceTimeBy(200)

        assertEquals("Full ROM", viewModel.exerciseNotes.value["exercise-1"])
    }

    @Test
    fun `prior bests loaded after session`() = runTest(testDispatcher) {
        val session = fakeSession()
        val prior = ExercisePrior(
            exerciseId = "exercise-1",
            exerciseName = "Squat",
            reps = 5,
            weight = 225.0,
            date = "2026-05-01",
        )
        whenever(repository.getSession("s1")).thenReturn(session)
        whenever(repository.getPriorBests("s1")).thenReturn(listOf(prior))

        viewModel.loadSession("s1")
        advanceTimeBy(200)

        assertEquals(prior, viewModel.priorBests.value["exercise-1"])
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
                targetReps = 8,
            )
        ),
    )
}
