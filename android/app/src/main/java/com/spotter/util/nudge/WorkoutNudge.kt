package com.spotter.util.nudge

/**
 * Pure decision logic for the workout-morning nudge (Tier W2b). Kept free of Android/Room types so
 * the "should we nudge, and with what copy?" rules are unit-testable in isolation from the
 * [WorkoutNudgeWorker] plumbing that gathers the inputs.
 *
 * The nudge is deliberately conservative — it fires only when the user opted in, notifications are
 * allowed, the fire time is outside quiet hours, today is a scheduled *workout* day of the active
 * program, and the user hasn't already started/finished a session today. Any other case is a Skip.
 */
object WorkoutNudge {

    sealed interface Decision {
        data class Show(val title: String, val text: String) : Decision
        data class Skip(val reason: String) : Decision
    }

    /**
     * True when [hour] falls inside the quiet-hours window [quietStartHour, quietEndHour) (local,
     * end-exclusive). Handles a window that wraps midnight (start > end). An empty window
     * (start == end) is treated as "no quiet hours".
     */
    fun isQuietHour(hour: Int, quietStartHour: Int, quietEndHour: Int): Boolean {
        if (quietStartHour == quietEndHour) return false
        return if (quietStartHour < quietEndHour) {
            hour in quietStartHour until quietEndHour
        } else {
            hour >= quietStartHour || hour < quietEndHour
        }
    }

    /** The short display name for the scheduled day — its program label, else the routine name. */
    fun displayName(dayLabel: String?, routineName: String?): String =
        dayLabel?.takeIf { it.isNotBlank() }
            ?: routineName?.takeIf { it.isNotBlank() }
            ?: "Workout"

    /**
     * @param enabled the opt-in Settings toggle.
     * @param notificationsAllowed OS-level notification permission/switch is on.
     * @param nowHour the local hour the worker is running (for the quiet-hours check).
     * @param quietStartHour / [quietEndHour] the quiet-hours window.
     * @param isWorkoutDayToday the active program schedules a (non-rest) workout for today.
     * @param alreadyTrainedToday a session is already completed or in progress today.
     * @param dayLabel / [routineName] copy inputs for the scheduled day.
     */
    fun decide(
        enabled: Boolean,
        notificationsAllowed: Boolean,
        nowHour: Int,
        quietStartHour: Int,
        quietEndHour: Int,
        isWorkoutDayToday: Boolean,
        alreadyTrainedToday: Boolean,
        dayLabel: String?,
        routineName: String?,
    ): Decision {
        if (!enabled) return Decision.Skip("disabled")
        if (!notificationsAllowed) return Decision.Skip("notifications-denied")
        if (isQuietHour(nowHour, quietStartHour, quietEndHour)) return Decision.Skip("quiet-hours")
        if (!isWorkoutDayToday) return Decision.Skip("not-a-workout-day")
        if (alreadyTrainedToday) return Decision.Skip("already-trained-today")

        val name = displayName(dayLabel, routineName)
        return Decision.Show(
            title = "$name today",
            text = "Ready when you are — you've got a $name workout scheduled.",
        )
    }
}
