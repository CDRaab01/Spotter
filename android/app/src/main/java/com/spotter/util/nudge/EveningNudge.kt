package com.spotter.util.nudge

import java.time.LocalDate

/**
 * Pure decision logic for the evening motivation nudges — the streak-saver ("your N-day
 * streak is on the line") and the comeback ("it's been a few days"). Same shape and
 * conventions as [WorkoutNudge]: Context-free so the rules are unit-testable, conservative
 * by default, and re-evaluated at fire time so a stale schedule can never nag.
 *
 * The two kinds are mutually exclusive by construction — the streak-saver needs a live
 * streak on a workout day, the comeback needs 2–3 already-missed days — so at most one
 * notification can fire per evening, with the streak-saver taking precedence.
 */
object EveningNudge {

    /** Notification ids continue the app's sequence (1003 = morning nudge). */
    enum class Kind(val notificationId: Int) {
        STREAK_SAVER(1004),
        COMEBACK(1005),
    }

    sealed interface Decision {
        data class Show(val kind: Kind, val title: String, val text: String) : Decision
        data class Skip(val reason: String) : Decision
    }

    /**
     * @param enabled the (shared) opt-in Settings toggle.
     * @param notificationsAllowed OS-level notification permission/switch is on.
     * @param nowHour the local hour the worker is running (quiet-hours check).
     * @param isWorkoutDayToday the active program schedules a (non-rest) workout for today.
     * @param trainedToday a strength or cardio session is completed (or in progress) today.
     * @param currentStreak [com.spotter.util.StreakCalculator.currentStreak] at fire time.
     * @param consecutiveMissedDays [com.spotter.util.StreakCalculator.consecutiveMissedWorkoutDays];
     *   0 for a never-trained user, large for a long-lapsed one — both fall outside the 2..3
     *   comeback window, which is what keeps this from nagging either.
     * @param alreadyNudgedThisEpisode the comeback latch: a comeback nudge was already posted
     *   for the current miss episode (identified by the last completed-session date).
     * @param today drives deterministic copy-variant rotation (stable for tests).
     */
    fun decide(
        enabled: Boolean,
        notificationsAllowed: Boolean,
        nowHour: Int,
        quietStartHour: Int,
        quietEndHour: Int,
        isWorkoutDayToday: Boolean,
        trainedToday: Boolean,
        currentStreak: Int,
        consecutiveMissedDays: Int,
        alreadyNudgedThisEpisode: Boolean,
        today: LocalDate,
    ): Decision {
        if (!enabled) return Decision.Skip("disabled")
        if (!notificationsAllowed) return Decision.Skip("notifications-denied")
        if (WorkoutNudge.isQuietHour(nowHour, quietStartHour, quietEndHour)) {
            return Decision.Skip("quiet-hours")
        }
        if (trainedToday) return Decision.Skip("already-trained-today")

        if (isWorkoutDayToday && currentStreak > 0) {
            val variant = variantFor(today, STREAK_SAVER_COPY.size)
            val (title, text) = STREAK_SAVER_COPY[variant](currentStreak)
            return Decision.Show(Kind.STREAK_SAVER, title, text)
        }

        if (consecutiveMissedDays in COMEBACK_MISSED_RANGE) {
            if (alreadyNudgedThisEpisode) return Decision.Skip("already-nudged-this-episode")
            val variant = variantFor(today, COMEBACK_COPY.size)
            val (title, text) = COMEBACK_COPY[variant]
            return Decision.Show(Kind.COMEBACK, title, text)
        }

        return Decision.Skip("nothing-to-say")
    }

    /** Fire the comeback only while the moment is fresh; after 3 misses it has passed. */
    val COMEBACK_MISSED_RANGE = 2..3

    private fun variantFor(today: LocalDate, count: Int): Int = today.dayOfYear % count

    // Copy lives here (not strings.xml) so decide() stays Context-free — same deliberate
    // trade as WorkoutNudge. Variants rotate by date so the nudge doesn't read canned.
    private val STREAK_SAVER_COPY: List<(Int) -> Pair<String, String>> = listOf(
        { n ->
            "Your $n-day streak is on the line" to
                "Still time to get today's workout in and keep it going."
        },
        { n ->
            "Don't break the chain" to
                "$n day${if (n != 1) "s" else ""} strong — today's workout keeps the streak alive."
        },
        { n ->
            "$n-day streak at stake" to
                "One workout tonight keeps your streak going."
        },
    )

    private val COMEBACK_COPY: List<Pair<String, String>> = listOf(
        "Ready when you are" to
            "It's been a few days — a short session tonight gets you back on track.",
        "Pick up where you left off" to
            "Missing a couple of days happens. Tonight's a good night for a comeback.",
        "Your program misses you" to
            "Even a light session counts. Jump back in tonight.",
    )
}
