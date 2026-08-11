package com.spotter.util

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure streak math shared by Home's stats and the evening nudge worker, so the
 * two can never disagree about what the current streak is. Kept free of
 * Android/Room types for unit testing in isolation ([WorkoutNudge]-style).
 *
 * Conventions (unchanged from the original HomeViewModel implementation):
 *  - `completedDates` is the per-day dedup of completed strength AND cardio sessions.
 *  - Scheduled rest days are transparent: they don't increment the streak but they
 *    don't break the chain either.
 *  - The streak anchors at today when trained or resting today, else at yesterday
 *    (grace day) — so an evening without a workout doesn't read as zero until the
 *    day after.
 */
object StreakCalculator {

    fun currentStreak(
        today: LocalDate,
        completedDates: Set<LocalDate>,
        restDayDates: Set<LocalDate>,
    ): Int {
        var day = when {
            completedDates.contains(today) -> today
            restDayDates.contains(today) -> today
            else -> today.minusDays(1)
        }
        var streak = 0
        while (completedDates.contains(day) || restDayDates.contains(day)) {
            if (!restDayDates.contains(day)) streak++
            day = day.minusDays(1)
        }
        return streak
    }

    /** The most recent completed-session date, or null when the user has never trained. */
    fun lastCompletedDate(completedDates: Set<LocalDate>): LocalDate? = completedDates.maxOrNull()

    /**
     * How many scheduled workout days in a row the user has missed, counting backwards
     * from yesterday (today isn't "missed" while it's still in progress — that's the
     * streak-saver's territory, not the comeback's).
     *
     * Two program shapes, mirroring [WorkoutProjection.effectiveCadence]:
     *  - [cadenceStep] <= 1 (the program encodes rest days): walk back day by day, rest
     *    days transparent, stopping at the first completed day or the [maxLookbackDays]
     *    floor. Every non-rest, non-completed day is a miss.
     *  - [cadenceStep] > 1 (every-N-days cadence, no rest days): scheduled dates are
     *    [anchorDate] + k*step; every one strictly before today is a miss.
     *
     * Returns 0 when [anchorDate] is null (never trained — a brand-new user is never
     * "behind"). A long-lapsed user returns a large count, which callers exclude with
     * a bounded fire window rather than being nagged here.
     */
    fun consecutiveMissedWorkoutDays(
        today: LocalDate,
        completedDates: Set<LocalDate>,
        restDayDates: Set<LocalDate>,
        cadenceStep: Int,
        anchorDate: LocalDate?,
        maxLookbackDays: Long = 30,
    ): Int {
        if (anchorDate == null) return 0
        if (cadenceStep <= 1) {
            var missed = 0
            var day = today.minusDays(1)
            val floor = today.minusDays(maxLookbackDays)
            while (!day.isBefore(floor)) {
                if (completedDates.contains(day)) break
                if (!restDayDates.contains(day)) missed++
                day = day.minusDays(1)
            }
            return missed
        }
        val daysSince = ChronoUnit.DAYS.between(anchorDate, today)
        if (daysSince <= 0) return 0
        return ((daysSince - 1) / cadenceStep).toInt()
    }
}
