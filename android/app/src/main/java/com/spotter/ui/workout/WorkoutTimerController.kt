package com.spotter.ui.workout

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.spotter.di.ApplicationScope
import com.spotter.util.TimeProvider
import com.spotter.util.WakeLockHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A live snapshot of the between-sets rest countdown. `null` state means "not resting". */
data class RestState(
    val remainingSec: Int,
    val durationSec: Int,
)

/**
 * Single source of truth for the workout rest/work cycle — the workout-side analogue of
 * [com.spotter.ui.cardio.CardioRunController]. It is drift-free (the countdown is recomputed from a
 * monotonic [TimeProvider.elapsedRealtimeMs] end-anchor, never a decrementing counter), runs in an
 * app-scoped coroutine so the end-of-rest cue fires even with the screen off, owns the rest wake-lock
 * itself (acquired synchronously inside [startRest], so the CPU can't sleep before the countdown
 * begins), and fires the completion vibration exactly once.
 *
 * Both [WorkoutViewModel] (for the on-screen ring) and [WorkoutSessionService] (for the merged
 * notification) only *read* [restState]; neither computes time independently, which is what keeps the
 * ring and the notification in lock-step. The elapsed session clock is NOT owned here — it is derived
 * everywhere from the persisted `startedAtMs` anchor.
 */
@Singleton
class WorkoutTimerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val time: TimeProvider,
    @ApplicationScope private val scope: CoroutineScope,
    private val restStore: RestTimerStore,
) {
    private val _restState = MutableStateFlow<RestState?>(null)
    val restState: StateFlow<RestState?> = _restState.asStateFlow()

    @Volatile private var countdownJob: Job? = null
    private val wakeLock = WakeLockHolder(context, WAKE_LOCK_TAG, MAX_WAKELOCK_MS)

    /**
     * Bumped on every [startRest]/[dismissRest]. A countdown only publishes/finalizes while its
     * captured generation is still current, so a superseded countdown (a back-to-back new rest, or a
     * skip landing exactly as the prior rest ends) can never clobber the newer state, release the
     * newer rest's wake-lock, or fire a stray cue. Volatile for cross-thread visibility.
     */
    @Volatile private var generation: Long = 0L

    /** Monotonic instant the current "working" stretch started (reset whenever a rest ends). */
    @Volatile private var workingSinceRealtime: Long = time.elapsedRealtimeMs()

    // Runs AFTER the fields above are initialized (wakeLock/generation): resuming a rest touches them.
    init {
        restorePendingRest()
    }

    /** Whole seconds spent working since the last rest ended (0-floored). For the on-screen strip. */
    fun workElapsedSec(): Int =
        ((time.elapsedRealtimeMs() - workingSinceRealtime) / 1000L).coerceAtLeast(0L).toInt()

    /**
     * Start (or restart) a [durationSec]-second rest. Idempotent/re-entrant: any prior countdown is
     * superseded and cancelled, and the wake-lock is (re)acquired without double-holding. The full
     * duration is published synchronously so the UI updates instantly; the drift-free countdown then
     * ticks it down.
     */
    fun startRest(durationSec: Int) {
        if (durationSec <= 0) return
        // Persist the wall-clock end so the countdown can be resumed after process death/reboot.
        restStore.save(endEpochMs = time.nowMs() + durationSec * 1000L, durationSec = durationSec)
        // A foreground start (user tapped through a set), so the service may be (re)started here.
        beginRest(remainingSec = durationSec, displayDurationSec = durationSec, ensureService = true)
    }

    /**
     * Resume a rest that was in flight when the process was killed. Reconstructs the remaining time
     * from the persisted wall-clock end, so reopening the app mid-rest picks up exactly where it left
     * off. Never starts the foreground service here (this runs during construction, possibly with the
     * app in the background); the normal in-progress-edge notifier owns the service.
     */
    private fun restorePendingRest() {
        val pending = restStore.read() ?: return
        val remaining = remainingSec(pending.endEpochMs, time.nowMs())
        if (remaining <= 0) {
            restStore.clear() // The rest elapsed while we were gone — the cue moment has passed.
            return
        }
        beginRest(remaining, pending.durationSec, ensureService = false)
    }

    /**
     * Drive a [durationSec]-display rest with [remainingSec] left. Shared by a fresh [startRest] and
     * a [restorePendingRest] resume. Idempotent/re-entrant: any prior countdown is superseded and
     * cancelled, and the wake-lock is (re)acquired without double-holding.
     */
    private fun beginRest(remainingSec: Int, displayDurationSec: Int, ensureService: Boolean) {
        if (remainingSec <= 0) return
        val gen = ++generation
        countdownJob?.cancel()
        val endRealtime = time.elapsedRealtimeMs() + remainingSec * 1000L
        _restState.value = RestState(remainingSec, displayDurationSec)
        wakeLock.acquire()
        // Ensure the foreground notification service is up so the process is protected for the whole
        // rest (normally already started on the in-progress edge; idempotent insurance).
        if (ensureService) WorkoutSessionService.start(context)
        countdownJob = scope.launch {
            while (isActive && generation == gen) {
                val remaining = remainingSec(endRealtime, time.elapsedRealtimeMs())
                if (remaining <= 0) break
                // MutableStateFlow dedups equal values, so collectors (ring + notification) only see
                // one update per whole second despite the finer poll cadence.
                _restState.value = RestState(remaining, displayDurationSec)
                delay(POLL_MS)
            }
            if (generation == gen) finishRest(vibrateCue = true)
        }
    }

    /** Cancel an in-progress rest *without* the completion cue (e.g. user tapped "Skip rest"). */
    fun dismissRest() {
        generation++
        countdownJob?.cancel()
        countdownJob = null
        finishRest(vibrateCue = false)
    }

    private fun finishRest(vibrateCue: Boolean) {
        restStore.clear() // No pending rest to restore once one ends or is skipped.
        workingSinceRealtime = time.elapsedRealtimeMs()
        _restState.value = null
        wakeLock.release()
        if (vibrateCue) vibrateDone()
    }

    /** Buzz at the end of rest — the single authoritative cue, fired even when backgrounded. */
    private fun vibrateDone() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val POLL_MS = 250L
        private const val WAKE_LOCK_TAG = "spotter:rest_timer"
        private const val MAX_WAKELOCK_MS = 30L * 60 * 1000 // 30min backstop

        /** Remaining whole seconds until [endRealtime], rounded up, floored at 0. Pure for testing. */
        fun remainingSec(endRealtime: Long, nowRealtime: Long): Int {
            val remainingMs = endRealtime - nowRealtime
            if (remainingMs <= 0) return 0
            return ((remainingMs + 999) / 1000).toInt()
        }
    }
}
