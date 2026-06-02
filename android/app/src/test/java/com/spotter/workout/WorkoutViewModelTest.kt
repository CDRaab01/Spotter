package com.spotter.workout

import android.content.Context
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
    private lateinit var context: Context
    private lateinit var viewModel: WorkoutViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
        context = mock()
        viewModel = WorkoutViewModel(repository, context)
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

    // ── Activate set / lift timer ──────────────────────────────────────────────

    @Test
    fun `activateSet sets activeSetId and initialises reps from targetReps`() = runTest(testDispatcher) {
        val setLog = fakeSetLog(targetReps = 10)

        viewModel.activateSet(setLog)

        assertEquals(setLog.id, viewModel.activeSetId.value)
        assertEquals(10, viewModel.activeSetReps.value)
    }

    @Test
    fun `activateSet starts liftSeconds counting`() = runTest(testDispatcher) {
        val setLog = fakeSetLog()
        val job = launch { viewModel.liftSeconds.collect {} }

        viewModel.activateSet(setLog)
        advanceTimeBy(3500)

        assertEquals(3, viewModel.liftSeconds.value)
        job.cancel()
    }

    @Test
    fun `activateSet falls back to reps when targetReps is null`() = runTest(testDispatcher) {
        val setLog = fakeSetLog(reps = 6, targetReps = null)

        viewModel.activateSet(setLog)

        assertEquals(6, viewModel.activeSetReps.value)
    }

    // ── Decrement reps ─────────────────────────────────────────────────────────

    @Test
    fun `decrementActiveReps decrements from targetReps`() = runTest(testDispatcher) {
        viewModel.activateSet(fakeSetLog(targetReps = 8))

        viewModel.decrementActiveReps()

        assertEquals(7, viewModel.activeSetReps.value)
    }

    @Test
    fun `decrementActiveReps does not go below 1`() = runTest(testDispatcher) {
        viewModel.activateSet(fakeSetLog(targetReps = 1))

        viewModel.decrementActiveReps()
        viewModel.decrementActiveReps()

        assertEquals(1, viewModel.activeSetReps.value)
    }

    // ── Complete active set ────────────────────────────────────────────────────

    @Test
    fun `completeActiveSet calls updateSet with completed=true and actual reps`() = runTest(testDispatcher) {
        val session = fakeSession()
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(completed = true))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.activateSet(setLog)
        viewModel.decrementActiveReps() // 8 → 7

        viewModel.completeActiveSet(session.id)
        advanceTimeBy(200)

        verify(repository).updateSet(
            session.id,
            setLog.id,
            SetLogUpdate(completed = true, reps = 7),
        )
    }

    @Test
    fun `completeActiveSet clears activeSetId`() = runTest(testDispatcher) {
        val session = fakeSession()
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(completed = true))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.activateSet(setLog)
        viewModel.completeActiveSet(session.id)
        advanceTimeBy(200)

        assertNull(viewModel.activeSetId.value)
    }

    @Test
    fun `completeActiveSet with full reps starts normal rest timer`() = runTest(testDispatcher) {
        val session = fakeSession() // targetReps = 8 → 90s base
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(completed = true))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.activateSet(setLog) // activeSetReps = 8 = targetReps → no failure

        viewModel.completeActiveSet(session.id)
        advanceTimeBy(200)

        assertEquals(90, viewModel.restTimerSeconds.value)
    }

    @Test
    fun `completeActiveSet on failure starts extended rest timer`() = runTest(testDispatcher) {
        val session = fakeSession() // targetReps = 8 → 90s base; failure → 90+60 = 150s
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(completed = true))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.activateSet(setLog)
        repeat(3) { viewModel.decrementActiveReps() } // 8 → 5 (< 8 = failure)

        viewModel.completeActiveSet(session.id)
        advanceTimeBy(200)

        assertEquals(150, viewModel.restTimerSeconds.value)
    }

    // ── Rest timer ─────────────────────────────────────────────────────────────

    @Test
    fun `startRestTimer adds 60s for failure`() = runTest(testDispatcher) {
        viewModel.startRestTimer(targetReps = 8, actualReps = 5) // 90 + 60
        advanceTimeBy(200)
        assertEquals(150, viewModel.restTimerSeconds.value)
    }

    @Test
    fun `startRestTimer no extra time on success`() = runTest(testDispatcher) {
        viewModel.startRestTimer(targetReps = 8, actualReps = 8)
        advanceTimeBy(200)
        assertEquals(90, viewModel.restTimerSeconds.value)
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

    // ── Other ──────────────────────────────────────────────────────────────────

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
        assertEquals(0, summaryEvents[0].doneSets)
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
    fun `elapsedSeconds increments every second while observed`() = runTest(testDispatcher) {
        assertEquals(0, viewModel.elapsedSeconds.value)
        val job = launch { viewModel.elapsedSeconds.collect {} }
        advanceTimeBy(3500)
        assertEquals(3, viewModel.elapsedSeconds.value)
        job.cancel()
    }

    // ── Superset routing ───────────────────────────────────────────────────────

    @Test
    fun `completeActiveSet with superset activates next set instead of starting rest`() = runTest(testDispatcher) {
        val set1 = fakeSetLog(id = "set-1", supersetGroup = 1)
        val set2 = fakeSetLog(id = "set-2", supersetGroup = 1)
        val session = SessionOut(
            id = "s1", userId = "user-1", planId = null, date = "2026-06-01",
            status = "in_progress", durationSeconds = null, note = null,
            setLogs = listOf(set1, set2),
        )
        whenever(repository.getSession("s1")).thenReturn(session)
        whenever(repository.getPriorBests("s1")).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(set1.copy(completed = true))

        viewModel.loadSession("s1")
        advanceTimeBy(200)
        viewModel.activateSet(set1)
        viewModel.completeActiveSet("s1")
        advanceTimeBy(200)

        assertEquals("set-2", viewModel.activeSetId.value)
        assertNull(viewModel.restTimerSeconds.value)
    }

    @Test
    fun `completeActiveSet without superset starts rest timer normally`() = runTest(testDispatcher) {
        val session = fakeSession()
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(completed = true))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.activateSet(setLog)
        viewModel.completeActiveSet(session.id)
        advanceTimeBy(200)

        assertNull(viewModel.activeSetId.value)
        assertNotNull(viewModel.restTimerSeconds.value)
    }

    @Test
    fun `completeActiveSet skips completed sets when looking for superset partner`() = runTest(testDispatcher) {
        val set1 = fakeSetLog(id = "set-1", supersetGroup = 1)
        val set2 = fakeSetLog(id = "set-2", supersetGroup = 1, completed = true)  // already done
        val session = SessionOut(
            id = "s1", userId = "user-1", planId = null, date = "2026-06-01",
            status = "in_progress", durationSeconds = null, note = null,
            setLogs = listOf(set1, set2),
        )
        whenever(repository.getSession("s1")).thenReturn(session)
        whenever(repository.getPriorBests("s1")).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(set1.copy(completed = true))

        viewModel.loadSession("s1")
        advanceTimeBy(200)
        viewModel.activateSet(set1)
        viewModel.completeActiveSet("s1")
        advanceTimeBy(200)

        // set2 is already completed so no superset partner → rest timer starts
        assertNull(viewModel.activeSetId.value)
        assertNotNull(viewModel.restTimerSeconds.value)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun fakeSetLog(
        id: String = "set-1",
        sessionId: String = "session-1",
        reps: Int = 8,
        targetReps: Int? = 8,
        supersetGroup: Int? = null,
        completed: Boolean = false,
    ) = SetLogOut(
        id = id,
        sessionId = sessionId,
        exerciseId = "exercise-1",
        setNumber = 1,
        reps = reps,
        weight = 135.0,
        completed = completed,
        targetReps = targetReps,
        supersetGroup = supersetGroup,
    )

    private fun fakeSession(id: String = "session-1") = SessionOut(
        id = id,
        userId = "user-1",
        planId = null,
        date = "2025-06-01",
        status = "in_progress",
        durationSeconds = null,
        note = null,
        setLogs = listOf(fakeSetLog(sessionId = id)),
    )
}
