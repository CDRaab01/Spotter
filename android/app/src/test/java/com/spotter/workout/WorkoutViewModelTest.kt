package com.spotter.workout

import android.content.Context
import com.spotter.data.model.ExerciseOut
import com.spotter.data.model.ExercisePrior
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SessionUpdate
import com.spotter.data.model.SetLogCreate
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.SetLogUpdate
import com.spotter.data.model.SuggestedAdjustmentAction
import com.spotter.data.repository.ExerciseRepository
import com.spotter.data.repository.SessionRepository
import com.spotter.ui.workout.WorkoutTimerController
import com.spotter.ui.workout.WorkoutViewModel
import com.spotter.util.AppPreferences
import com.spotter.util.TimeProvider
import com.spotter.util.UiState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: SessionRepository
    private lateinit var exerciseRepository: ExerciseRepository
    private lateinit var appPreferences: AppPreferences
    private lateinit var context: Context
    private lateinit var time: FakeTimeProvider
    private lateinit var viewModel: WorkoutViewModel

    /**
     * Wall-clock ([nowMs]) is a settable value so elapsed assertions are deterministic; the
     * monotonic clock ([elapsedRealtimeMs]) tracks the test scheduler so rest countdowns and the
     * work count-up advance with virtual time (and the countdown loop always terminates).
     */
    private class FakeTimeProvider(private val scheduler: TestCoroutineScheduler) : TimeProvider {
        var nowMsValue: Long = 0
        override fun nowMs(): Long = nowMsValue
        override fun elapsedRealtimeMs(): Long = scheduler.currentTime
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository = mock()
        exerciseRepository = mock()
        appPreferences = mock()
        context = mock()
        time = FakeTimeProvider(testDispatcher.scheduler)
        whenever(appPreferences.trackRpe).thenReturn(flowOf(false))
        whenever(appPreferences.autoStartRest).thenReturn(flowOf(true))
        // Suspend mocks default to null (not an empty map) — stub the override lookup globally.
        wheneverBlocking { repository.getRestSeconds(any()) }.thenReturn(emptyMap())
        viewModel = createViewModel()
    }

    private fun createViewModel(): WorkoutViewModel {
        // read() returns null by default → no pending rest to resume in tests.
        val timer = WorkoutTimerController(context, time, CoroutineScope(testDispatcher), mock())
        return WorkoutViewModel(repository, exerciseRepository, timer, time, appPreferences)
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

    // ── Complete / toggle set ───────────────────────────────────────────────────

    @Test
    fun `toggleComplete marks set complete with reps and weight and starts rest`() = runTest(testDispatcher) {
        val session = fakeSession() // targetReps = 8 → 90s base
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(completed = true))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)

        viewModel.toggleComplete(session.id, setLog, reps = 8, weightLbs = 135.0)
        advanceTimeBy(200)

        verify(repository).updateSet(
            session.id,
            setLog.id,
            SetLogUpdate(completed = true, reps = 8, weight = 135.0),
        )
        assertEquals(90, viewModel.restTimerSeconds.value)
        val updated = (viewModel.session.value as UiState.Success).data.setLogs.first()
        assertEquals(true, updated.completed)
    }

    @Test
    fun `toggleComplete on a completed set un-completes it and dismisses rest`() = runTest(testDispatcher) {
        val completed = fakeSetLog(completed = true)
        val session = fakeSession().copy(setLogs = listOf(completed))
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(completed.copy(completed = false))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.startRestTimer(8)
        advanceTimeBy(200)
        assertNotNull(viewModel.restTimerSeconds.value)

        viewModel.toggleComplete(session.id, completed, reps = 8, weightLbs = 135.0)
        advanceTimeBy(200)

        verify(repository).updateSet(
            session.id,
            completed.id,
            SetLogUpdate(completed = false, reps = 8, weight = 135.0),
        )
        assertNull(viewModel.restTimerSeconds.value)
    }

    @Test
    fun `toggleComplete with reps below target starts extended rest timer`() = runTest(testDispatcher) {
        val session = fakeSession() // targetReps = 8 → 90s base; failure → 150s
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(completed = true))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)

        viewModel.toggleComplete(session.id, setLog, reps = 5, weightLbs = 135.0)
        advanceTimeBy(200)

        assertEquals(150, viewModel.restTimerSeconds.value)
    }

    // ── Work timer ───────────────────────────────────────────────────────────────

    @Test
    fun `workSeconds counts up while not resting`() = runTest(testDispatcher) {
        val job = launch { viewModel.workSeconds.collect {} }
        advanceTimeBy(3500)
        assertEquals(3, viewModel.workSeconds.value)
        job.cancel()
    }

    @Test
    fun `workSeconds stays at zero while resting`() = runTest(testDispatcher) {
        val job = launch { viewModel.workSeconds.collect {} }
        viewModel.startRestTimer(8)
        advanceTimeBy(3500)
        assertEquals(0, viewModel.workSeconds.value)
        job.cancel()
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
    fun `elapsedSeconds reflects time since the session start anchor`() = runTest(testDispatcher) {
        val session = fakeSession()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        // Anchor 3s in the past; elapsed is derived from (now - startedAtMs), not a counter.
        time.nowMsValue = 100_000
        whenever(repository.getStartedAtMs(session.id)).thenReturn(97_000L)

        assertEquals(0, viewModel.elapsedSeconds.value)
        val job = launch { viewModel.elapsedSeconds.collect {} }
        viewModel.loadSession(session.id)
        advanceTimeBy(1100)

        assertEquals(3, viewModel.elapsedSeconds.value)
        job.cancel()
    }

    @Test
    fun `finishSession sends anchor-derived duration`() = runTest(testDispatcher) {
        val session = fakeSession()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        time.nowMsValue = 100_000
        whenever(repository.getStartedAtMs(session.id)).thenReturn(95_000L) // 5s elapsed
        whenever(repository.updateSession(any(), any())).thenReturn(session.copy(status = "completed"))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.finishSession(session.id)
        advanceTimeBy(200)

        val captor = argumentCaptor<SessionUpdate>()
        verify(repository).updateSession(eq(session.id), captor.capture())
        assertEquals(5, captor.firstValue.durationSeconds)
    }

    @Test
    fun `finishSession failure surfaces actionError and resets finishState`() = runTest(testDispatcher) {
        val session = fakeSession()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSession(any(), any())).thenThrow(RuntimeException("network down"))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.finishSession(session.id)
        advanceTimeBy(200)

        // Previously the error was parked in finishState (read only for Loading) — invisible.
        assertNotNull(viewModel.actionError.value)
        assertIs<UiState.Idle>(viewModel.finishState.value)
    }

    @Test
    fun `applyProgression sends adjust_weight with routine write-back`() = runTest(testDispatcher) {
        val session = fakeSession()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.applyAdjustment(any(), any(), any())).thenReturn(session)
        val prior = ExercisePrior(
            exerciseId = "exercise-1",
            exerciseName = "Squat",
            reps = 8,
            weight = 135.0,
            date = "2026-05-01",
            suggestedWeight = 140.0,
            suggestedReps = 8,
            suggestedReason = "All sets at 8+ reps — add weight",
            action = "add_weight",
        )

        viewModel.applyProgression(session.id, prior)
        advanceTimeBy(200)

        val actions = argumentCaptor<List<SuggestedAdjustmentAction>>()
        val toRoutine = argumentCaptor<Boolean>()
        verify(repository).applyAdjustment(eq(session.id), actions.capture(), toRoutine.capture())
        val action = actions.firstValue.single()
        assertEquals("adjust_weight", action.type)
        assertEquals("exercise-1", action.exerciseId)
        assertEquals(140.0, action.weight)
        assertEquals(8, action.reps)
        // The write-back is the point: next session pre-fills at the new load.
        assertEquals(true, toRoutine.firstValue)
    }

    @Test
    fun `applyProgression without a suggested weight is a no-op`() = runTest(testDispatcher) {
        val prior = ExercisePrior(
            exerciseId = "exercise-1",
            reps = 8,
            date = "2026-05-01",
            suggestedReason = "add reps",
            action = "add_reps",
        )

        viewModel.applyProgression("session-1", prior)
        advanceTimeBy(200)

        verify(repository, org.mockito.kotlin.never()).applyAdjustment(any(), any(), any())
    }

    @Test
    fun `applyProgression failure surfaces actionError`() = runTest(testDispatcher) {
        whenever(repository.applyAdjustment(any(), any(), any()))
            .thenThrow(RuntimeException("offline"))
        val prior = ExercisePrior(
            exerciseId = "exercise-1",
            exerciseName = "Squat",
            reps = 8,
            date = "2026-05-01",
            suggestedWeight = 140.0,
            action = "add_weight",
        )

        viewModel.applyProgression("session-1", prior)
        advanceTimeBy(200)

        assertNotNull(viewModel.actionError.value)
        viewModel.clearActionError()
        assertNull(viewModel.actionError.value)
    }

    // ── Set types / RPE / deletion ─────────────────────────────────────────────

    @Test
    fun `setSetType patches state and sends the payload`() = runTest(testDispatcher) {
        val session = fakeSession()
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(setType = "warmup"))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.setSetType(session.id, setLog, "warmup")
        advanceTimeBy(200)

        verify(repository).updateSet(session.id, setLog.id, SetLogUpdate(setType = "warmup"))
        val patched = (viewModel.session.value as UiState.Success).data.setLogs.first()
        assertEquals("warmup", patched.setType)
    }

    @Test
    fun `setRpe clamps into bounds and sends the payload`() = runTest(testDispatcher) {
        val session = fakeSession()
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(rpe = 10.0))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.setRpe(session.id, setLog, 12.0) // over the top → clamped to 10
        advanceTimeBy(200)

        verify(repository).updateSet(session.id, setLog.id, SetLogUpdate(rpe = 10.0))
        val patched = (viewModel.session.value as UiState.Success).data.setLogs.first()
        assertEquals(10.0, patched.rpe)
    }

    @Test
    fun `deleteSet delegates to the repository and reloads`() = runTest(testDispatcher) {
        val session = fakeSession()
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.deleteSet(session.id, setLog)
        advanceTimeBy(200)

        verify(repository).deleteSet(session.id, setLog.id)
        // Reload after deletion (initial load + the post-delete one).
        verify(repository, org.mockito.kotlin.times(2)).getSession(session.id)
        assertNull(viewModel.actionError.value)
    }

    @Test
    fun `deleteSet failure surfaces actionError`() = runTest(testDispatcher) {
        val setLog = fakeSetLog()
        whenever(repository.deleteSet(any(), any())).thenThrow(RuntimeException("409"))

        viewModel.deleteSet("session-1", setLog)
        advanceTimeBy(200)

        assertNotNull(viewModel.actionError.value)
    }

    // ── Manual exercise management ─────────────────────────────────────────────

    @Test
    fun `addExercise logs three fresh sets carrying the display name`() = runTest(testDispatcher) {
        val session = fakeSession()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.logSet(any(), any(), anyOrNull())).thenReturn(fakeSetLog())
        val exercise = ExerciseOut(id = "ex-9", name = "Face Pull", muscleGroup = "rear delts")

        viewModel.addExercise(session.id, exercise)
        advanceTimeBy(200)

        val creates = argumentCaptor<SetLogCreate>()
        verify(repository, org.mockito.kotlin.times(3))
            .logSet(eq(session.id), creates.capture(), eq("Face Pull"))
        assertEquals(listOf(1, 2, 3), creates.allValues.map { it.setNumber })
        creates.allValues.forEach {
            assertEquals("ex-9", it.exerciseId)
            assertEquals(false, it.completed)
            assertNull(it.weight) // no invented load — the user logs the real one
        }
    }

    @Test
    fun `removeExercise deletes only the incomplete sets`() = runTest(testDispatcher) {
        val done = fakeSetLog(id = "set-done", completed = true)
        val todo1 = fakeSetLog(id = "set-todo1")
        val todo2 = fakeSetLog(id = "set-todo2")
        val other = fakeSetLog(id = "set-other").copy(exerciseId = "exercise-2")
        val session = fakeSession().copy(setLogs = listOf(done, todo1, todo2, other))
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.removeExercise(session.id, "exercise-1")
        advanceTimeBy(200)

        verify(repository).deleteSet(session.id, "set-todo1")
        verify(repository).deleteSet(session.id, "set-todo2")
        // Completed sets are immutable history; other exercises are untouched.
        verify(repository, org.mockito.kotlin.never()).deleteSet(session.id, "set-done")
        verify(repository, org.mockito.kotlin.never()).deleteSet(session.id, "set-other")
    }

    // ── Auto-start gating + per-exercise rest override ─────────────────────────

    @Test
    fun `auto-start off queues the rest instead of starting it`() = runTest(testDispatcher) {
        whenever(appPreferences.autoStartRest).thenReturn(flowOf(false))
        viewModel = createViewModel()
        val session = fakeSession() // targetReps 8 → 90s heuristic
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(completed = true))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.toggleComplete(session.id, setLog, reps = 8, weightLbs = 135.0)
        advanceTimeBy(200)

        assertNull(viewModel.restTimerSeconds.value)
        assertEquals(90, viewModel.pendingRestDuration.value)

        viewModel.startPendingRest()
        advanceTimeBy(200)

        assertEquals(90, viewModel.restTimerSeconds.value)
        assertNull(viewModel.pendingRestDuration.value)
    }

    @Test
    fun `routine rest override replaces the rep-range heuristic`() = runTest(testDispatcher) {
        val session = fakeSession() // heuristic would be 90s (8 reps)
        val setLog = session.setLogs.first()
        whenever(repository.getSession(session.id)).thenReturn(session)
        whenever(repository.getPriorBests(session.id)).thenReturn(emptyList())
        whenever(repository.getRestSeconds(session.id)).thenReturn(mapOf("exercise-1" to 45))
        whenever(repository.updateSet(any(), any(), any())).thenReturn(setLog.copy(completed = true))

        viewModel.loadSession(session.id)
        advanceTimeBy(200)
        viewModel.toggleComplete(session.id, setLog, reps = 5, weightLbs = 135.0)
        advanceTimeBy(200)

        // The prescription is exact: no heuristic, no failure bump (5 < 8 would have added 60s).
        assertEquals(45, viewModel.restTimerSeconds.value)
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
        routineId = null,
        date = "2025-06-01",
        status = "in_progress",
        durationSeconds = null,
        note = null,
        setLogs = listOf(fakeSetLog(sessionId = id)),
    )
}
