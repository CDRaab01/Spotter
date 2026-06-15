package com.spotter.workout

import com.spotter.ui.workout.WorkoutTimerController
import org.junit.Test
import kotlin.test.assertEquals

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
}
