package com.spotter.workout

import com.spotter.ui.workout.PendingRest
import com.spotter.ui.workout.RestTimerStore
import com.spotter.ui.workout.WorkoutTimerController
import com.spotter.util.TimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rest countdown's remaining-seconds math is the drift-sensitive core: it is recomputed from a
 * monotonic end-anchor every poll, so a coalesced/late tick can never make the rest end late. These
 * assertions pin that behavior (rounds up, floors at zero).
 */
class WorkoutTimerControllerTest {

    @Test
    fun `remainingSec rounds up partial seconds`() {
        // 89.8s left → shows 90, not 89, so the displayed countdown never undershoots.
        assertEquals(90, WorkoutTimerController.remainingSec(endRealtime = 90_000, nowRealtime = 200))
        assertEquals(89, WorkoutTimerController.remainingSec(endRealtime = 90_000, nowRealtime = 1_000))
    }

    @Test
    fun `remainingSec is the full duration at the start`() {
        assertEquals(90, WorkoutTimerController.remainingSec(endRealtime = 90_000, nowRealtime = 0))
    }

    @Test
    fun `remainingSec floors at zero once elapsed`() {
        assertEquals(0, WorkoutTimerController.remainingSec(endRealtime = 90_000, nowRealtime = 90_000))
        assertEquals(0, WorkoutTimerController.remainingSec(endRealtime = 90_000, nowRealtime = 95_000))
    }

    /** A persisted rest whose wall-clock end is still in the future resumes with the remaining time,
     *  against its original display duration — so reopening the app mid-rest picks up where it left off. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `resumes a persisted rest with the remaining time`() = runTest {
        val time = object : TimeProvider {
            override fun nowMs(): Long = 1_000_000L
            override fun elapsedRealtimeMs(): Long = testScheduler.currentTime
        }
        // Ends 60s from now, originally a 90s rest.
        val store = FakeRestStore(PendingRest(endEpochMs = 1_060_000L, durationSec = 90))

        val controller = WorkoutTimerController(mock(), time, backgroundScope, store)

        val state = controller.restState.value
        assertEquals(60, state?.remainingSec)
        assertEquals(90, state?.durationSec)
    }

    /** A persisted rest that already elapsed while the process was dead is discarded (no stale ring,
     *  no late cue) and cleared from the store. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `discards and clears an already-elapsed persisted rest`() = runTest {
        val time = object : TimeProvider {
            override fun nowMs(): Long = 2_000_000L
            override fun elapsedRealtimeMs(): Long = testScheduler.currentTime
        }
        val store = FakeRestStore(PendingRest(endEpochMs = 1_900_000L, durationSec = 90)) // 100s ago

        val controller = WorkoutTimerController(mock(), time, backgroundScope, store)

        assertNull(controller.restState.value)
        assertTrue(store.cleared)
    }

    private class FakeRestStore(private var pending: PendingRest?) : RestTimerStore {
        var cleared = false
        override fun save(endEpochMs: Long, durationSec: Int) {
            pending = PendingRest(endEpochMs, durationSec)
        }
        override fun read(): PendingRest? = pending
        override fun clear() {
            pending = null
            cleared = true
        }
    }
}
