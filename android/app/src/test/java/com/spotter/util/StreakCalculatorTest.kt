package com.spotter.util

import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreakCalculatorTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 10)

    // ── currentStreak (behavior transplanted from HomeViewModel) ──────────────

    @Test
    fun `streak is zero with no completed sessions`() {
        assertEquals(0, StreakCalculator.currentStreak(today, emptySet(), emptySet()))
    }

    @Test
    fun `consecutive days count up to today`() {
        val completed = setOf(today, today.minusDays(1), today.minusDays(2))
        assertEquals(3, StreakCalculator.currentStreak(today, completed, emptySet()))
    }

    @Test
    fun `yesterday counts as grace day when today not trained`() {
        val completed = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, StreakCalculator.currentStreak(today, completed, emptySet()))
    }

    @Test
    fun `a two-day gap breaks the streak`() {
        val completed = setOf(today.minusDays(2), today.minusDays(3))
        assertEquals(0, StreakCalculator.currentStreak(today, completed, emptySet()))
    }

    @Test
    fun `rest days are transparent - no increment, no break`() {
        val completed = setOf(today, today.minusDays(2))
        val rest = setOf(today.minusDays(1))
        assertEquals(2, StreakCalculator.currentStreak(today, completed, rest))
    }

    @Test
    fun `a rest day today anchors the streak at today`() {
        val completed = setOf(today.minusDays(1))
        val rest = setOf(today)
        assertEquals(1, StreakCalculator.currentStreak(today, completed, rest))
    }

    // ── lastCompletedDate ─────────────────────────────────────────────────────

    @Test
    fun `lastCompletedDate is null for a never-trained user`() {
        assertNull(StreakCalculator.lastCompletedDate(emptySet()))
    }

    @Test
    fun `lastCompletedDate picks the most recent date`() {
        val completed = setOf(today.minusDays(5), today.minusDays(2), today.minusDays(9))
        assertEquals(today.minusDays(2), StreakCalculator.lastCompletedDate(completed))
    }

    // ── consecutiveMissedWorkoutDays — calendar-structured programs (step 1) ──

    @Test
    fun `no anchor means zero missed days`() {
        assertEquals(
            0,
            StreakCalculator.consecutiveMissedWorkoutDays(
                today, emptySet(), emptySet(), cadenceStep = 1, anchorDate = null,
            ),
        )
    }

    @Test
    fun `trained yesterday means zero missed days`() {
        val completed = setOf(today.minusDays(1))
        assertEquals(
            0,
            StreakCalculator.consecutiveMissedWorkoutDays(
                today, completed, emptySet(), 1, today.minusDays(1),
            ),
        )
    }

    @Test
    fun `two skipped workout days count as two misses`() {
        val completed = setOf(today.minusDays(3))
        assertEquals(
            2,
            StreakCalculator.consecutiveMissedWorkoutDays(
                today, completed, emptySet(), 1, today.minusDays(3),
            ),
        )
    }

    @Test
    fun `rest days between misses are not counted as misses`() {
        // Missed the workout 3 days ago and yesterday; 2 days ago was scheduled rest.
        val completed = setOf(today.minusDays(4))
        val rest = setOf(today.minusDays(2))
        assertEquals(
            2,
            StreakCalculator.consecutiveMissedWorkoutDays(
                today, completed, rest, 1, today.minusDays(4),
            ),
        )
    }

    @Test
    fun `lookback cap bounds the walk for long-lapsed users`() {
        val completed = setOf(today.minusDays(200))
        val missed = StreakCalculator.consecutiveMissedWorkoutDays(
            today, completed, emptySet(), 1, today.minusDays(200), maxLookbackDays = 30,
        )
        assertEquals(30, missed)
    }

    // ── consecutiveMissedWorkoutDays — cadence programs (step N) ──────────────

    @Test
    fun `cadence program with next slot today has zero misses`() {
        // Trained 2 days ago on an every-2-days cadence: today is the next slot.
        assertEquals(
            0,
            StreakCalculator.consecutiveMissedWorkoutDays(
                today, setOf(today.minusDays(2)), emptySet(), 2, today.minusDays(2),
            ),
        )
    }

    @Test
    fun `cadence program counts each skipped slot`() {
        // Trained 7 days ago on an every-2-days cadence: slots at -5, -3, -1 all missed.
        assertEquals(
            3,
            StreakCalculator.consecutiveMissedWorkoutDays(
                today, setOf(today.minusDays(7)), emptySet(), 2, today.minusDays(7),
            ),
        )
    }

    @Test
    fun `cadence program one missed slot`() {
        // Trained 3 days ago on an every-2-days cadence: slot at -1 missed, next is tomorrow.
        assertEquals(
            1,
            StreakCalculator.consecutiveMissedWorkoutDays(
                today, setOf(today.minusDays(3)), emptySet(), 2, today.minusDays(3),
            ),
        )
    }

    @Test
    fun `anchor today on a cadence program has zero misses`() {
        assertEquals(
            0,
            StreakCalculator.consecutiveMissedWorkoutDays(
                today, setOf(today), emptySet(), 3, today,
            ),
        )
    }
}
