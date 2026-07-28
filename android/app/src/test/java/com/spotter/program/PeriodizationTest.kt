package com.spotter.program

import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.ui.program.programWeek
import com.spotter.ui.program.weekLabel
import org.junit.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeriodizationTest {

    private val today = LocalDate.of(2026, 7, 28)

    private fun program(
        weeks: Int? = null,
        deloadWeek: Int? = null,
        startedOn: String? = null,
    ) = WorkoutProgramEntity(
        id = "p1", name = "Block", isActive = true,
        weeks = weeks, deloadWeek = deloadWeek, startedOn = startedOn,
    )

    @Test
    fun `an open-ended program has no week info`() {
        assertNull(programWeek(program(), today))
        assertNull(weekLabel(program(), today))
    }

    @Test
    fun `a started block reports the current week`() {
        // Started 2026-07-13: day 15 → week 3.
        val week = programWeek(program(weeks = 8, startedOn = "2026-07-13"), today)!!
        assertEquals(3, week.currentWeek)
        assertEquals(8, week.totalWeeks)
        assertEquals("Week 3 of 8", weekLabel(program(weeks = 8, startedOn = "2026-07-13"), today))
    }

    @Test
    fun `week 1 covers the first seven days`() {
        assertEquals(1, programWeek(program(weeks = 4, startedOn = "2026-07-28"), today)!!.currentWeek)
        assertEquals(1, programWeek(program(weeks = 4, startedOn = "2026-07-22"), today)!!.currentWeek)
        assertEquals(2, programWeek(program(weeks = 4, startedOn = "2026-07-21"), today)!!.currentWeek)
    }

    @Test
    fun `a block that has run long holds at its last week`() {
        assertEquals(4, programWeek(program(weeks = 4, startedOn = "2026-01-01"), today)!!.currentWeek)
    }

    @Test
    fun `a planned but unstarted block shows its length only`() {
        val week = programWeek(program(weeks = 6), today)!!
        assertNull(week.currentWeek)
        assertFalse(week.isDeloadWeek)
        assertEquals("6-week program", weekLabel(program(weeks = 6), today))
    }

    @Test
    fun `the deload week is flagged only in that week`() {
        // Week 3 of the block.
        assertTrue(
            programWeek(program(weeks = 8, deloadWeek = 3, startedOn = "2026-07-13"), today)!!
                .isDeloadWeek
        )
        assertFalse(
            programWeek(program(weeks = 8, deloadWeek = 4, startedOn = "2026-07-13"), today)!!
                .isDeloadWeek
        )
    }

    @Test
    fun `an unparseable start date degrades to the length label`() {
        assertEquals("8-week program", weekLabel(program(weeks = 8, startedOn = "not-a-date"), today))
    }
}
