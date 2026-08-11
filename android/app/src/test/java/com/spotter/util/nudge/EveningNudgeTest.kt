package com.spotter.util.nudge

import java.time.LocalDate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EveningNudgeTest {

    private val today: LocalDate = LocalDate.of(2026, 8, 10)

    /** Baseline inputs that produce a streak-saver Show; each test flips one knob. */
    private fun decide(
        enabled: Boolean = true,
        notificationsAllowed: Boolean = true,
        nowHour: Int = 18,
        quietStartHour: Int = 21,
        quietEndHour: Int = 7,
        isWorkoutDayToday: Boolean = true,
        trainedToday: Boolean = false,
        currentStreak: Int = 5,
        consecutiveMissedDays: Int = 0,
        alreadyNudgedThisEpisode: Boolean = false,
        date: LocalDate = today,
    ) = EveningNudge.decide(
        enabled = enabled,
        notificationsAllowed = notificationsAllowed,
        // The cases below are written in whole hours; decide() works in minutes since midnight.
        nowMinuteOfDay = nowHour * 60,
        quietStartMinuteOfDay = quietStartHour * 60,
        quietEndMinuteOfDay = quietEndHour * 60,
        isWorkoutDayToday = isWorkoutDayToday,
        trainedToday = trainedToday,
        currentStreak = currentStreak,
        consecutiveMissedDays = consecutiveMissedDays,
        alreadyNudgedThisEpisode = alreadyNudgedThisEpisode,
        today = date,
    )

    // ── Guards ────────────────────────────────────────────────────────────────

    @Test
    fun `disabled skips`() {
        val d = decide(enabled = false)
        assertEquals("disabled", (d as EveningNudge.Decision.Skip).reason)
    }

    @Test
    fun `denied notifications skip`() {
        val d = decide(notificationsAllowed = false)
        assertEquals("notifications-denied", (d as EveningNudge.Decision.Skip).reason)
    }

    @Test
    fun `quiet hours skip`() {
        val d = decide(nowHour = 22)
        assertEquals("quiet-hours", (d as EveningNudge.Decision.Skip).reason)
    }

    @Test
    fun `already trained today skips both kinds`() {
        val d = decide(trainedToday = true, consecutiveMissedDays = 2)
        assertEquals("already-trained-today", (d as EveningNudge.Decision.Skip).reason)
    }

    // ── Streak-saver ──────────────────────────────────────────────────────────

    @Test
    fun `workout day with a live streak fires the streak-saver`() {
        val d = decide()
        val show = assertIs<EveningNudge.Decision.Show>(d)
        assertEquals(EveningNudge.Kind.STREAK_SAVER, show.kind)
    }

    @Test
    fun `streak-saver copy contains the streak count`() {
        // Check all copy variants (rotated by date) mention the number.
        for (offset in 0L..2L) {
            val d = decide(currentStreak = 7, date = today.plusDays(offset))
            val show = assertIs<EveningNudge.Decision.Show>(d)
            assertTrue(
                "7" in show.title || "7" in show.text,
                "variant for ${today.plusDays(offset)} lacks the count: $show",
            )
        }
    }

    @Test
    fun `no streak means no streak-saver`() {
        val d = decide(currentStreak = 0)
        assertEquals("nothing-to-say", (d as EveningNudge.Decision.Skip).reason)
    }

    @Test
    fun `rest day with a streak does not fire the streak-saver`() {
        val d = decide(isWorkoutDayToday = false)
        assertIs<EveningNudge.Decision.Skip>(d)
    }

    @Test
    fun `streak-saver wins over comeback when both could apply`() {
        // Contrived (a live streak with missed days), but precedence must be explicit.
        val d = decide(currentStreak = 3, consecutiveMissedDays = 2)
        val show = assertIs<EveningNudge.Decision.Show>(d)
        assertEquals(EveningNudge.Kind.STREAK_SAVER, show.kind)
    }

    // ── Comeback ──────────────────────────────────────────────────────────────

    private fun comeback(missed: Int, latched: Boolean = false) = decide(
        isWorkoutDayToday = false,
        currentStreak = 0,
        consecutiveMissedDays = missed,
        alreadyNudgedThisEpisode = latched,
    )

    @Test
    fun `comeback fires at 2 missed days`() {
        val show = assertIs<EveningNudge.Decision.Show>(comeback(2))
        assertEquals(EveningNudge.Kind.COMEBACK, show.kind)
    }

    @Test
    fun `comeback fires at 3 missed days`() {
        val show = assertIs<EveningNudge.Decision.Show>(comeback(3))
        assertEquals(EveningNudge.Kind.COMEBACK, show.kind)
    }

    @Test
    fun `one missed day is too early for the comeback`() {
        assertEquals("nothing-to-say", (comeback(1) as EveningNudge.Decision.Skip).reason)
    }

    @Test
    fun `four missed days is past the comeback window`() {
        assertEquals("nothing-to-say", (comeback(4) as EveningNudge.Decision.Skip).reason)
    }

    @Test
    fun `never-trained user (zero missed) gets no comeback`() {
        assertEquals("nothing-to-say", (comeback(0) as EveningNudge.Decision.Skip).reason)
    }

    @Test
    fun `latched episode suppresses the comeback`() {
        val d = comeback(2, latched = true)
        assertEquals("already-nudged-this-episode", (d as EveningNudge.Decision.Skip).reason)
    }

    @Test
    fun `distinct notification ids per kind`() {
        assertEquals(1004, EveningNudge.Kind.STREAK_SAVER.notificationId)
        assertEquals(1005, EveningNudge.Kind.COMEBACK.notificationId)
    }
}
