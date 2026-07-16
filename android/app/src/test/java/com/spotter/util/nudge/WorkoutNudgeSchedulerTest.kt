package com.spotter.util.nudge

import org.junit.Assert.assertEquals
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
}
