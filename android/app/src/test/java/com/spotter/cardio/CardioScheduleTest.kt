package com.spotter.cardio

import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.model.CardioStatus
import com.spotter.ui.cardio.CardioPrograms
import com.spotter.ui.cardio.CardioSchedule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CardioScheduleTest {

    private val program = CardioPrograms.c25k
    private val today = LocalDate.of(2026, 6, 15)

    private fun completed(week: Int, day: Int, date: LocalDate) = CardioSessionEntity(
        id = "$week-$day",
        programId = CardioPrograms.C25K_ID,
        weekNumber = week,
        dayNumber = day,
        // Local midnight, not UTC: CardioFormat.parseDate resolves instants to the DEVICE-LOCAL
        // day (matching how sessions are stamped in production), so a midnight-UTC fixture
        // parsed to "yesterday" in any zone west of Greenwich — this was the whole
        // timezone-sensitivity of this test.
        startedAt = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toString(),
        completedAt = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toString(),
        status = CardioStatus.COMPLETED,
        totalElapsedSec = 600,
    )

    @Test
    fun `first run targets today when nothing is completed`() {
        val upcoming = CardioSchedule.upcoming(program, emptyList(), today, count = 3)

        assertEquals(3, upcoming.size)
        assertEquals(today, upcoming.first().date)
        // The first run is Week 1 Day 1.
        assertEquals(1, upcoming.first().cardio?.week)
        assertEquals(1, upcoming.first().cardio?.day)
        // Every entry carries a cardio payload and no strength routine.
        assertTrue(upcoming.all { it.cardio != null && it.routineId == null })
    }

    @Test
    fun `upcoming skips completed days and projects forward on cadence`() {
        // Completed Week 1 Day 1 yesterday → next is W1D2, +2 days from that completion (= tomorrow,
        // which is still in the future so it isn't clamped to today).
        val w1d1Date = today.minusDays(1)
        val upcoming = CardioSchedule.upcoming(
            program,
            listOf(completed(1, 1, w1d1Date)),
            today,
            count = 1,
        )

        val next = upcoming.single()
        assertEquals(1, next.cardio?.week)
        assertEquals(2, next.cardio?.day)
        // Cadence[1] = 2 days after the W1D1 completion.
        assertEquals(w1d1Date.plusDays(2), next.date)
    }

    @Test
    fun `overdue target is clamped to today`() {
        // Completed a long time ago so the projected next date would be in the past.
        val upcoming = CardioSchedule.upcoming(
            program,
            listOf(completed(1, 1, today.minusDays(30))),
            today,
            count = 1,
        )

        assertEquals(today, upcoming.single().date)
    }

    @Test
    fun `non-guided program yields no upcoming runs`() {
        val upcoming = CardioSchedule.upcoming(CardioPrograms.freeRun, emptyList(), today, count = 3)
        assertTrue(upcoming.isEmpty())
    }

    @Test
    fun `ordered days cover the whole guided plan`() {
        val ordered = CardioSchedule.orderedDays(program)
        val expected = program.weeks!!.sumOf { it.days.size }
        assertEquals(expected, ordered.size)
        assertNotNull(ordered.firstOrNull { it.week == 1 && it.day == 1 })
    }
}
