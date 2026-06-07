package com.spotter.util

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class WorkoutProjectionTest {

    private val today = LocalDate.of(2026, 6, 2)

    private fun day(routineId: String) = ProjectionDay(routineId = routineId, label = routineId, routineName = routineId)

    @Test
    fun `no sessions starts today and spaces by cadence`() {
        val days = listOf(day("A"), day("B"))
        val slots = WorkoutProjection.project(today, cadenceDays = 3, anchor = null, days = days, count = 2)

        assertEquals(2, slots.size)
        assertEquals(today, slots[0].date)
        assertEquals("A", slots[0].routineId)
        assertEquals(today.plusDays(3), slots[1].date)
        assertEquals("B", slots[1].routineId)
    }

    @Test
    fun `no active program days yields empty`() {
        val slots = WorkoutProjection.project(today, cadenceDays = 2, anchor = null, days = emptyList(), count = 2)
        assertTrue(slots.isEmpty())
    }

    @Test
    fun `single day program cycles to the same day`() {
        val days = listOf(day("A"))
        val slots = WorkoutProjection.project(today, cadenceDays = 2, anchor = null, days = days, count = 2)

        assertEquals(listOf("A", "A"), slots.map { it.routineId })
        assertEquals(today, slots[0].date)
        assertEquals(today.plusDays(2), slots[1].date)
    }

    @Test
    fun `in progress session today projects next day at today plus cadence`() {
        val days = listOf(day("A"), day("B"), day("C"))
        val anchor = SessionAnchor(date = today, routineId = "A", status = "in_progress")
        val slots = WorkoutProjection.project(today, cadenceDays = 2, anchor = anchor, days = days, count = 2)

        assertEquals(today.plusDays(2), slots[0].date)
        assertEquals("B", slots[0].routineId)
        assertEquals(today.plusDays(4), slots[1].date)
        assertEquals("C", slots[1].routineId)
    }

    @Test
    fun `stale past anchor rolls forward to first date on or after today`() {
        val days = listOf(day("A"), day("B"))
        val anchor = SessionAnchor(date = today.minusDays(5), routineId = "A", status = "completed")
        val slots = WorkoutProjection.project(today, cadenceDays = 2, anchor = anchor, days = days, count = 2)

        // (today-5)+2=-3, +2=-1, +2=+1 -> first projected date is today+1
        assertEquals(today.plusDays(1), slots[0].date)
        assertTrue(!slots[0].date.isBefore(today))
        assertEquals(today.plusDays(3), slots[1].date)
    }

    @Test
    fun `completing earlier shifts the schedule earlier`() {
        val days = listOf(day("A"), day("B"))
        val onTime = WorkoutProjection.project(
            today, 3, SessionAnchor(today, "A", "completed"), days, 1,
        )
        val early = WorkoutProjection.project(
            today, 3, SessionAnchor(today.minusDays(1), "A", "completed"), days, 1,
        )

        assertTrue(early[0].date.isBefore(onTime[0].date))
        assertEquals(1, onTime[0].date.toEpochDay() - early[0].date.toEpochDay())
    }

    @Test
    fun `anchor routine not in program falls back to first day`() {
        val days = listOf(day("A"), day("B"))
        val anchor = SessionAnchor(date = today, routineId = "X", status = "completed")
        val slots = WorkoutProjection.project(today, cadenceDays = 2, anchor = anchor, days = days, count = 2)

        assertEquals("A", slots[0].routineId)
        assertEquals("B", slots[1].routineId)
    }

    @Test
    fun `cycling wraps around the ordered days`() {
        val days = listOf(day("A"), day("B"), day("C"))
        val anchor = SessionAnchor(date = today, routineId = "B", status = "completed")
        val slots = WorkoutProjection.project(today, cadenceDays = 1, anchor = anchor, days = days, count = 2)

        assertEquals(listOf("C", "A"), slots.map { it.routineId })
    }

    private fun restDay() = ProjectionDay(routineId = null, label = "Rest", routineName = null)

    @Test
    fun `program with rest days uses step 1 regardless of cadence`() {
        // 4 workout + 3 rest = 7-day cycle; cadence pref = 2 should be ignored
        val days = listOf(day("Upper1"), day("Lower1"), restDay(), day("Upper2"), day("Lower2"), restDay(), restDay())
        val slots = WorkoutProjection.project(today, cadenceDays = 2, anchor = null, days = days, count = 7)

        // Every slot is exactly 1 day apart
        for (i in 1 until slots.size) {
            assertEquals(1L, slots[i].date.toEpochDay() - slots[i - 1].date.toEpochDay())
        }
        // 4 out of 7 days are workout days
        assertEquals(4, slots.count { it.routineId != null })
        // Cycle completes in 7 calendar days
        assertEquals(today.plusDays(6), slots.last().date)
    }

    @Test
    fun `effectiveCadence returns 1 when rest days present`() {
        val withRest = listOf(day("A"), restDay())
        assertEquals(1, WorkoutProjection.effectiveCadence(2, withRest))
    }

    @Test
    fun `effectiveCadence returns cadence when no rest days`() {
        val noRest = listOf(day("A"), day("B"), day("C"))
        assertEquals(3, WorkoutProjection.effectiveCadence(3, noRest))
    }
}
