package com.spotter.util

import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class WorkoutProjectionTest {

    private val today = LocalDate.of(2026, 6, 2)

    private fun day(planId: String) = ProjectionDay(planId = planId, label = planId, planName = planId)

    @Test
    fun `no sessions starts today and spaces by cadence`() {
        val days = listOf(day("A"), day("B"))
        val slots = WorkoutProjection.project(today, cadenceDays = 3, anchor = null, days = days, count = 2)

        assertEquals(2, slots.size)
        assertEquals(today, slots[0].date)
        assertEquals("A", slots[0].planId)
        assertEquals(today.plusDays(3), slots[1].date)
        assertEquals("B", slots[1].planId)
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

        assertEquals(listOf("A", "A"), slots.map { it.planId })
        assertEquals(today, slots[0].date)
        assertEquals(today.plusDays(2), slots[1].date)
    }

    @Test
    fun `in progress session today projects next day at today plus cadence`() {
        val days = listOf(day("A"), day("B"), day("C"))
        val anchor = SessionAnchor(date = today, planId = "A", status = "in_progress")
        val slots = WorkoutProjection.project(today, cadenceDays = 2, anchor = anchor, days = days, count = 2)

        assertEquals(today.plusDays(2), slots[0].date)
        assertEquals("B", slots[0].planId)
        assertEquals(today.plusDays(4), slots[1].date)
        assertEquals("C", slots[1].planId)
    }

    @Test
    fun `stale past anchor rolls forward to first date on or after today`() {
        val days = listOf(day("A"), day("B"))
        val anchor = SessionAnchor(date = today.minusDays(5), planId = "A", status = "completed")
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
    fun `anchor plan not in program falls back to first day`() {
        val days = listOf(day("A"), day("B"))
        val anchor = SessionAnchor(date = today, planId = "X", status = "completed")
        val slots = WorkoutProjection.project(today, cadenceDays = 2, anchor = anchor, days = days, count = 2)

        assertEquals("A", slots[0].planId)
        assertEquals("B", slots[1].planId)
    }

    @Test
    fun `cycling wraps around the ordered days`() {
        val days = listOf(day("A"), day("B"), day("C"))
        val anchor = SessionAnchor(date = today, planId = "B", status = "completed")
        val slots = WorkoutProjection.project(today, cadenceDays = 1, anchor = anchor, days = days, count = 2)

        assertEquals(listOf("C", "A"), slots.map { it.planId })
    }
}
