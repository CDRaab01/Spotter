package com.spotter.cardio

import android.content.Context
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.model.CardioPhase
import com.spotter.data.model.CardioStatus
import com.spotter.data.model.Interval
import com.spotter.data.repository.CardioRepository
import com.spotter.ui.cardio.CardioPrograms
import com.spotter.ui.cardio.CardioRunController
import com.spotter.util.TimeProvider
import com.spotter.util.WakeLockHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cardio run's battery contract: the tick loop and the partial wake-lock exist only while the
 * run is actually running. Pause must drop both (a paused run with the phone locked used to pin the
 * CPU awake for up to 6 h), resume must bring both back, and neither a forgotten Free Run nor a
 * forgotten pause may hold anything forever. Time is the scheduler's virtual clock throughout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardioRunControllerTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var repository: CardioRepository
    private lateinit var wakeLock: FakeWakeLock
    private lateinit var scope: CoroutineScope
    private lateinit var controller: CardioRunController

    private class FakeTimeProvider(private val scheduler: TestCoroutineScheduler) : TimeProvider {
        override fun nowMs(): Long = scheduler.currentTime
        override fun elapsedRealtimeMs(): Long = scheduler.currentTime
    }

    /** Records holds instead of touching PowerManager (which is a null stub on the JVM). */
    private class FakeWakeLock(context: Context) : WakeLockHolder(context, "test", 0L) {
        var held = false
        var acquireCount = 0
        var lastTimeoutMs = -1L

        override fun acquire(timeoutMs: Long) {
            held = true
            acquireCount++
            lastTimeoutMs = timeoutMs
        }

        override fun release() {
            held = false
        }
    }

    private val session = CardioSessionEntity(
        id = "local-1",
        programId = CardioPrograms.FREE_RUN_ID,
        startedAt = "2026-09-19T12:00:00Z",
        status = CardioStatus.IN_PROGRESS,
    )

    // 60s warm-up + 120s run = a 3-minute guided plan.
    private val intervals = listOf(Interval(CardioPhase.WARM_UP, 60), Interval(CardioPhase.RUN, 120))

    @Before
    fun setup() {
        context = mock()
        repository = mock()
        wakeLock = FakeWakeLock(context)
        scope = CoroutineScope(testDispatcher)
        controller = CardioRunController(
            context, repository, FakeTimeProvider(testDispatcher.scheduler), scope, wakeLock,
        )
    }

    /** The run's loop is endless by design, so every test tears the scope down explicitly. */
    private fun controllerTest(body: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        whenever(repository.startSession(any(), anyOrNull(), anyOrNull())).thenReturn(session)
        try {
            body()
        } finally {
            scope.cancel()
        }
    }

    private fun startGuided(resume: CardioSessionEntity? = null) =
        controller.startGuided("c25k", 1, 1, intervals, "Couch to 5K", "Week 1 · Day 1", resume)

    @Test
    fun `a running run ticks and holds the wake-lock`() = controllerTest {
        startGuided()
        advanceTimeBy(10_500)

        assertTrue(wakeLock.held)
        assertEquals(10, controller.state.value?.totalElapsedSec)
        assertFalse(controller.state.value!!.isPaused)
    }

    @Test
    fun `pause stops the loop and releases the wake-lock but keeps the service up`() = controllerTest {
        startGuided()
        advanceTimeBy(10_500)

        controller.pause()
        runCurrent()
        assertFalse(wakeLock.held)
        assertTrue(controller.state.value!!.isPaused)
        verify(repository).updateProgress("local-1", 10)

        // Nothing is scheduled on the ~200ms tick cadence any more: the only pending work is the
        // long idle-exit timer, so a minute of wall time passes without a single tick or persist.
        advanceTimeBy(60_000)
        assertEquals(10, controller.state.value?.totalElapsedSec)
        verify(repository).updateProgress(any(), any()) // still just the one from pause()
        verify(context, org.mockito.kotlin.never()).stopService(any())
    }

    @Test
    fun `resume re-acquires the wake-lock and restarts the loop from the frozen time`() = controllerTest {
        startGuided()
        advanceTimeBy(10_500)
        controller.pause()
        advanceTimeBy(5 * 60_000L)

        controller.resume()
        assertTrue(wakeLock.held)
        assertEquals(2, wakeLock.acquireCount)
        advanceTimeBy(5_000)

        // The five paused minutes never counted.
        assertEquals(15, controller.state.value?.totalElapsedSec)
        assertFalse(controller.state.value!!.isPaused)
    }

    @Test
    fun `guided wake-lock timeout is the remaining plan time plus margin`() = controllerTest {
        startGuided()
        assertEquals(180_000L + CardioRunController.WAKELOCK_MARGIN_MS, wakeLock.lastTimeoutMs)

        advanceTimeBy(30_500)
        controller.pause()
        controller.resume()
        assertEquals(150_000L + CardioRunController.WAKELOCK_MARGIN_MS, wakeLock.lastTimeoutMs)
    }

    @Test
    fun `a run restored after process death starts running from the persisted elapsed`() = controllerTest {
        startGuided(resume = session.copy(programId = "c25k", totalElapsedSec = 100))
        advanceTimeBy(5_500)

        assertTrue(wakeLock.held)
        assertEquals(80_000L + CardioRunController.WAKELOCK_MARGIN_MS, wakeLock.lastTimeoutMs)
        assertEquals(105, controller.state.value?.totalElapsedSec)
        verify(repository, org.mockito.kotlin.never()).startSession(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a guided run completes and releases the wake-lock`() = controllerTest {
        startGuided()
        advanceTimeBy(181_000)

        assertTrue(controller.state.value!!.isComplete)
        assertFalse(wakeLock.held)
        verify(repository).completeSession("local-1", 180)
    }

    @Test
    fun `an open-ended run is auto-paused at the segment cap, not left running`() = controllerTest {
        controller.startFree(openEnded = true, intervals = emptyList())
        assertEquals(
            CardioRunController.OPEN_ENDED_MAX_SEGMENT_MS + CardioRunController.WAKELOCK_MARGIN_MS,
            wakeLock.lastTimeoutMs,
        )

        advanceTimeBy(CardioRunController.OPEN_ENDED_MAX_SEGMENT_MS - 1_000)
        assertTrue(wakeLock.held)
        assertFalse(controller.state.value!!.isPaused)

        advanceTimeBy(2_000)
        val capSec = (CardioRunController.OPEN_ENDED_MAX_SEGMENT_MS / 1000).toInt()
        assertFalse(wakeLock.held)
        assertTrue(controller.state.value!!.isPaused)
        assertFalse(controller.state.value!!.isComplete)
        assertEquals(capSec, controller.state.value?.totalElapsedSec)
        verify(repository, atLeastOnce()).updateProgress(eq("local-1"), eq(capSec))

        // The cap is per un-paused stretch: resuming gets a fresh one rather than re-pausing at once.
        controller.resume()
        advanceTimeBy(60_500)
        assertTrue(wakeLock.held)
        assertEquals(capSec + 60, controller.state.value?.totalElapsedSec)
    }

    @Test
    fun `a run left paused exits itself - persisted, service stopped, state cleared`() = controllerTest {
        startGuided()
        advanceTimeBy(10_500)
        controller.pause()

        advanceTimeBy(CardioRunController.PAUSED_IDLE_EXIT_MS - 1_000)
        assertNotNull(controller.state.value)

        advanceTimeBy(2_000)
        assertNull(controller.state.value)
        assertFalse(wakeLock.held)
        verify(context).stopService(any())
        // Persisted at pause and again at exit, both at the frozen 10s — the session stays resumable.
        verify(repository, atLeastOnce()).updateProgress("local-1", 10)
        verify(repository, org.mockito.kotlin.never()).completeSession(any(), any())
    }

    @Test
    fun `resuming disarms the idle exit`() = controllerTest {
        startGuided()
        controller.pause()
        advanceTimeBy(CardioRunController.PAUSED_IDLE_EXIT_MS - 1_000)
        controller.resume()

        advanceTimeBy(60_000)
        assertNotNull(controller.state.value)
        assertTrue(wakeLock.held)
        verify(context, org.mockito.kotlin.never()).stopService(any())
    }

    @Test
    fun `pauseAndExit from a running run releases everything`() = controllerTest {
        startGuided()
        advanceTimeBy(20_500)

        controller.pauseAndExit()
        runCurrent()
        assertNull(controller.state.value)
        assertFalse(wakeLock.held)
        verify(context).stopService(any())
        verify(repository).updateProgress("local-1", 20)

        // Controls on a dead run are inert — resume must not resurrect a loop with no state.
        controller.resume()
        assertFalse(wakeLock.held)
        assertNull(controller.state.value)
    }

    @Test
    fun `wakeLockTimeoutMs never goes below the margin`() {
        assertEquals(
            CardioRunController.WAKELOCK_MARGIN_MS,
            CardioRunController.wakeLockTimeoutMs(openEnded = false, totalDurationSec = 100, elapsedSec = 500),
        )
    }
}
