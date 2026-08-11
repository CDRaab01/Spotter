package com.spotter.util.nudge

import com.spotter.util.TimeOfDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WorkoutNudgeSchedulerTest {

    private val zone: ZoneId = ZoneId.of("America/New_York")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): ZonedDateTime =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone)

    @Test
    fun `before target time same day schedules today`() {
        // 6:30 -> 8:00 today = 90 minutes.
        val delay = WorkoutNudgeScheduler.nextRunDelayMillis(at(2026, 7, 16, 6, 30), targetHour = 8)
        assertEquals(90L * 60 * 1000, delay)
    }

    @Test
    fun `after target time schedules tomorrow`() {
        // 9:00 -> 8:00 next day = 23 hours.
        val delay = WorkoutNudgeScheduler.nextRunDelayMillis(at(2026, 7, 16, 9, 0), targetHour = 8)
        assertEquals(23L * 60 * 60 * 1000, delay)
    }

    @Test
    fun `exactly at target time schedules tomorrow`() {
        // 8:00 is not "before" 8:00, so next run is tomorrow (full 24h).
        val delay = WorkoutNudgeScheduler.nextRunDelayMillis(at(2026, 7, 16, 8, 0), targetHour = 8)
        assertEquals(24L * 60 * 60 * 1000, delay)
    }

    @Test
    fun `delay is never negative`() {
        val delay = WorkoutNudgeScheduler.nextRunDelayMillis(at(2026, 7, 16, 23, 59), targetHour = 8)
        assert(delay >= 0)
    }

    @Test
    fun `evening target hour schedules the 18h slot`() {
        // 12:00 -> 18:00 today = 6 hours.
        val delay = WorkoutNudgeScheduler.nextRunDelayMillis(
            at(2026, 7, 16, 12, 0), targetHour = 18,
        )
        assertEquals(6L * 60 * 60 * 1000, delay)
    }

    @Test
    fun `a target minute is honoured, not rounded to the hour`() {
        // 6:00 -> 8:15 today = 135 minutes. The picker shows minutes, so the schedule must keep them.
        val delay = WorkoutNudgeScheduler.nextRunDelayMillis(
            at(2026, 7, 16, 6, 0), targetHour = 8, targetMinute = 15,
        )
        assertEquals(135L * 60 * 1000, delay)
    }

    @Test
    fun `a target minute already passed today schedules tomorrow`() {
        // 8:30 is past 8:15, so the next run is 23h45m away.
        val delay = WorkoutNudgeScheduler.nextRunDelayMillis(
            at(2026, 7, 16, 8, 30), targetHour = 8, targetMinute = 15,
        )
        assertEquals((23L * 60 + 45) * 60 * 1000, delay)
    }

    // ── Schedule signature ────────────────────────────────────────────────────
    // The signature is what lets sync() tell "nothing changed, leave the running work alone" from
    // "the user moved a time, tear it down and re-enqueue". Without it, WorkManager's KEEP policy
    // treats an existing work as satisfying the request and silently drops the new fire time.

    @Test
    fun `signature is stable for the same schedule`() {
        assertEquals(
            WorkoutNudgeScheduler.signatureOf(true, TimeOfDay(8, 0), TimeOfDay(18, 0)),
            WorkoutNudgeScheduler.signatureOf(true, TimeOfDay(8, 0), TimeOfDay(18, 0)),
        )
    }

    @Test
    fun `signature changes when a nudge time moves`() {
        val before = WorkoutNudgeScheduler.signatureOf(true, TimeOfDay(8, 0), TimeOfDay(18, 0))
        assertNotEquals(before, WorkoutNudgeScheduler.signatureOf(true, TimeOfDay(7, 0), TimeOfDay(18, 0)))
        assertNotEquals(before, WorkoutNudgeScheduler.signatureOf(true, TimeOfDay(8, 30), TimeOfDay(18, 0)))
        assertNotEquals(before, WorkoutNudgeScheduler.signatureOf(true, TimeOfDay(8, 0), TimeOfDay(19, 0)))
    }

    @Test
    fun `signature changes when the toggle flips`() {
        assertNotEquals(
            WorkoutNudgeScheduler.signatureOf(true, TimeOfDay(8, 0), TimeOfDay(18, 0)),
            WorkoutNudgeScheduler.signatureOf(false, TimeOfDay(8, 0), TimeOfDay(18, 0)),
        )
    }

    @Test
    fun `after the evening slot schedules tomorrow evening`() {
        // 20:00 -> 18:00 next day = 22 hours.
        val delay = WorkoutNudgeScheduler.nextRunDelayMillis(
            at(2026, 7, 16, 20, 0), targetHour = 18,
        )
        assertEquals(22L * 60 * 60 * 1000, delay)
    }
}
