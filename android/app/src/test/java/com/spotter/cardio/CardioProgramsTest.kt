package com.spotter.cardio

import com.spotter.data.model.CardioPhase
import com.spotter.data.model.CardioProgramType
import com.spotter.ui.cardio.CardioPrograms
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CardioProgramsTest {

    @Test
    fun `c25k is 8 weeks of 3 days`() {
        val weeks = CardioPrograms.c25k.weeks
        assertNotNull(weeks)
        assertEquals(8, weeks.size)
        weeks.forEach { week -> assertEquals(3, week.days.size, "week ${week.weekNumber}") }
    }

    @Test
    fun `every c25k day's intervals sum to its stated total`() {
        CardioPrograms.c25k.weeks!!.forEach { week ->
            week.days.forEach { day ->
                val sum = day.intervals.sumOf { it.durationSec }
                assertEquals(
                    day.totalDurationSec,
                    sum,
                    "week ${week.weekNumber} day ${day.dayNumber} intervals must sum to total",
                )
            }
        }
    }

    @Test
    fun `every c25k day starts with a warm-up and ends with a cool-down`() {
        CardioPrograms.c25k.weeks!!.forEach { week ->
            week.days.forEach { day ->
                assertEquals(
                    CardioPhase.WARM_UP,
                    day.intervals.first().phase,
                    "week ${week.weekNumber} day ${day.dayNumber} should warm up first",
                )
                assertEquals(
                    CardioPhase.COOL_DOWN,
                    day.intervals.last().phase,
                    "week ${week.weekNumber} day ${day.dayNumber} should cool down last",
                )
                assertEquals(CardioPrograms.WARM_UP_SEC, day.intervals.first().durationSec)
                assertEquals(CardioPrograms.COOL_DOWN_SEC, day.intervals.last().durationSec)
            }
        }
    }

    @Test
    fun `c25k finishes at a continuous 30 minute run`() {
        val finalWeek = CardioPrograms.c25k.weeks!!.last()
        finalWeek.days.forEach { day ->
            val runSecs = day.intervals.filter { it.phase == CardioPhase.RUN }
            assertEquals(1, runSecs.size, "final week should be one continuous run")
            assertEquals(30 * 60, runSecs.first().durationSec)
            // No walk breaks in the final week.
            assertTrue(day.intervals.none { it.phase == CardioPhase.WALK })
        }
    }

    @Test
    fun `free run is open with no weeks`() {
        val free = CardioPrograms.byId(CardioPrograms.FREE_RUN_ID)
        assertNotNull(free)
        assertEquals(CardioProgramType.FREE, free.type)
        assertEquals(null, free.weeks)
    }

    @Test
    fun `dayIntervals resolves a known guided day`() {
        val intervals = CardioPrograms.dayIntervals(CardioPrograms.C25K_ID, week = 2, day = 1)
        assertNotNull(intervals)
        // Week 2 totals 31 minutes (matches the overview screenshot).
        assertEquals(31 * 60, intervals.sumOf { it.durationSec })
    }
}
