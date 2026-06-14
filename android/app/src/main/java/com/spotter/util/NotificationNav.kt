package com.spotter.util

import android.content.Intent

/**
 * Shared keys + parsing for notification deep-links. The in-progress foreground services
 * ([com.spotter.ui.workout.WorkoutSessionService], [com.spotter.ui.cardio.CardioRunService])
 * attach these extras to their tap PendingIntent; [com.spotter.MainActivity] reads them and
 * emits a [DeepLinkTarget] onto the [DeepLinkBus] for the nav graph to act on.
 */
object NotificationNav {
    const val EXTRA_NAV_TARGET = "com.spotter.nav.TARGET"
    const val EXTRA_SESSION_ID = "com.spotter.nav.SESSION_ID"

    const val TARGET_WORKOUT = "workout"
    const val TARGET_CARDIO = "cardio"

    /** Parse a launch/new intent into a deep-link target, or null if it carries none. */
    fun parse(intent: Intent?): DeepLinkTarget? {
        val target = intent?.getStringExtra(EXTRA_NAV_TARGET) ?: return null
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return null
        return when (target) {
            TARGET_WORKOUT -> DeepLinkTarget.Workout(sessionId)
            TARGET_CARDIO -> DeepLinkTarget.Cardio(sessionId)
            else -> null
        }
    }
}

/** A destination requested from a notification tap. */
sealed interface DeepLinkTarget {
    data class Workout(val sessionId: String) : DeepLinkTarget
    data class Cardio(val sessionId: String) : DeepLinkTarget
}
