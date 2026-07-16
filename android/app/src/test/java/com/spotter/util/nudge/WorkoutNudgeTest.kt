package com.spotter.util.nudge

import com.spotter.util.nudge.WorkoutNudge.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutNudgeTest {

    private fun decide(
        enabled: Boolean = true,
        notificationsAllowed: Boolean = true,
        nowHour: Int = 8,
        quietStartHour: Int = 21,
        quietEndHour: Int = 7,
        isWorkoutDayToday: Boolean = true,
        alreadyTrainedToday: Boolean = false,
        dayLabel: String? = "Push",
        routineName: String? = "Push Day A",
    ) = WorkoutNudge.decide(
        enabled = enabled,
        notificationsAllowed = notificationsAllowed,
        nowHour = nowHour,
        quietStartHour = quietStartHour,
        quietEndHour = quietEndHour,
        isWorkoutDayToday = isWorkoutDayToday,
        alreadyTrainedToday = alreadyTrainedToday,
        dayLabel = dayLabel,
        routineName = routineName,
    )

    @Test
    fun `fires on a scheduled workout day`() {
        val d = decide()
        assertTrue(d is Decision.Show)
        d as Decision.Show
        assertEquals("Push today", d.title)
        assertTrue(d.text.contains("Push"))
    }

    @Test
    fun `disabled toggle never fires`() {
        assertEquals(Decision.Skip("disabled"), decide(enabled = false))
    }

    @Test
    fun `denied notifications never fire`() {
        assertEquals(Decision.Skip("notifications-denied"), decide(notificationsAllowed = false))
    }

    @Test
    fun `rest day does not fire`() {
        assertEquals(Decision.Skip("not-a-workout-day"), decide(isWorkoutDayToday = false))
    }

    @Test
    fun `never nags when already trained today`() {
        assertEquals(Decision.Skip("already-trained-today"), decide(alreadyTrainedToday = true))
    }

    @Test
    fun `suppressed inside quiet hours`() {
        // 6:00 falls inside the default 21->7 (overnight) quiet window.
        assertEquals(Decision.Skip("quiet-hours"), decide(nowHour = 6))
    }

    @Test
    fun `falls back to routine name when day label blank`() {
        val d = decide(dayLabel = "  ", routineName = "Full Body")
        assertTrue(d is Decision.Show)
        assertEquals("Full Body today", (d as Decision.Show).title)
    }

    @Test
    fun `falls back to generic name when nothing named`() {
        val d = decide(dayLabel = null, routineName = null)
        assertEquals("Workout today", (d as Decision.Show).title)
    }

    @Test
    fun `quiet hours overnight window wraps midnight`() {
        // 21..7 wrapping window: 22 and 3 are quiet; 8 and 20 are not.
        assertTrue(WorkoutNudge.isQuietHour(22, 21, 7))
        assertTrue(WorkoutNudge.isQuietHour(3, 21, 7))
        assertFalse(WorkoutNudge.isQuietHour(8, 21, 7))
        assertFalse(WorkoutNudge.isQuietHour(20, 21, 7))
    }

    @Test
    fun `quiet hours same-day window is end-exclusive`() {
        // 9..17 daytime window.
        assertTrue(WorkoutNudge.isQuietHour(9, 9, 17))
        assertTrue(WorkoutNudge.isQuietHour(16, 9, 17))
        assertFalse(WorkoutNudge.isQuietHour(17, 9, 17))
        assertFalse(WorkoutNudge.isQuietHour(8, 9, 17))
    }

    @Test
    fun `empty quiet window disables quiet hours`() {
        assertFalse(WorkoutNudge.isQuietHour(3, 0, 0))
    }
}
