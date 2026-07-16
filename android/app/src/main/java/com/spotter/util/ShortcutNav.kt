package com.spotter.util

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Static launcher shortcuts (long-press the app icon). Each shortcut fires an
 * `spotter://shortcut/<target>` VIEW intent at [com.spotter.MainActivity]; the activity captures
 * the target into [ShortcutBus]. Because Spotter gates on auth (login) before the main graph, the
 * target is *held* rather than acted on immediately — it's honoured only once the user reaches the
 * authenticated graph (Coach is routed by the nav graph; Start-workout / Log-weight by Home).
 */
object ShortcutNav {
    const val SCHEME = "spotter"

    /** Start or resume today's workout (the active session, else the next scheduled routine). */
    const val TARGET_START_WORKOUT = "start_workout"

    /** Open the bodyweight-entry surface. */
    const val TARGET_LOG_WEIGHT = "log_weight"

    /** Open the AI coach chat. */
    const val TARGET_COACH = "coach"

    private val KNOWN = setOf(TARGET_START_WORKOUT, TARGET_LOG_WEIGHT, TARGET_COACH)

    /** The `spotter://shortcut/<target>` segment of a launcher-shortcut VIEW intent, else null. */
    fun parse(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        if (intent.data?.scheme != SCHEME) return null
        return intent.data?.lastPathSegment?.takeIf { it in KNOWN }
    }
}

/**
 * App-scoped holder for a pending launcher-shortcut target. [com.spotter.MainActivity] sets it on
 * launch/re-launch; the nav graph and Home observe [pending] and [consume] the one they handle.
 * Kept as a `@Singleton` (not Activity/VM state) so a shortcut tapped while signed out survives the
 * login flow — it's honoured after sign-in rather than dropped.
 */
@Singleton
class ShortcutBus @Inject constructor() {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun set(target: String?) {
        if (target != null) _pending.value = target
    }

    /** Clear [target] if it's still the pending one (a no-op if something else replaced it). */
    fun consume(target: String) {
        _pending.compareAndSet(target, null)
    }
}
