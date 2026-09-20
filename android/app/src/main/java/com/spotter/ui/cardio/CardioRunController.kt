package com.spotter.ui.cardio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.model.CardioPhase
import com.spotter.data.model.Interval
import com.spotter.data.repository.CardioRepository
import com.spotter.di.ApplicationScope
import com.spotter.util.TimeProvider
import com.spotter.util.WakeLockHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A live snapshot of the active cardio run, observed by the run screen and notification. */
data class CardioRunState(
    val intervals: List<Interval>,
    val currentIndex: Int,
    val phase: CardioPhase,
    val intervalElapsedSec: Int,
    val intervalRemainingSec: Int,   // -1 for open-ended (count-up)
    val intervalDurationSec: Int,
    val totalElapsedSec: Int,
    val totalDurationSec: Int,        // 0 for open-ended
    val isPaused: Boolean,
    val isComplete: Boolean,
    val isOpenEnded: Boolean,
    val label: String,
    val weekDayLabel: String?,
) {
    val isWarmup: Boolean get() = phase == CardioPhase.WARM_UP
}

private data class RunPlan(
    val programId: String,
    val week: Int?,
    val day: Int?,
    val intervals: List<Interval>,
    val openEnded: Boolean,
    val label: String,
    val weekDayLabel: String?,
)

/**
 * The drift-free cardio timer. Time is measured from [TimeProvider.elapsedRealtimeMs] deltas, not
 * a tick counter, so coalesced ticks or a backgrounded screen never accumulate error — the displayed
 * time is always recomputed from a monotonic baseline. The loop lives in an app-scoped coroutine
 * and is kept alive in the background by [CardioRunService] (a foreground service), so cues still
 * fire and progress still persists while the phone is locked.
 *
 * Battery contract: the tick loop and the partial wake-lock exist **only while the run is actually
 * running**. The controller owns the wake-lock itself (like
 * [com.spotter.ui.workout.WorkoutTimerController]) because only it knows when the run pauses,
 * resumes and how long the plan can legitimately take; the service is just the notification.
 * - Paused ⇒ no loop, no wake-lock. The foreground service stays up (cheap without the lock) so the
 *   user can come back to the run, but a single delayed coroutine exits a run left paused for
 *   [PAUSED_IDLE_EXIT_MS] — persisted and resumable, exactly like [pauseAndExit].
 * - The wake-lock timeout is derived from the plan (remaining duration + margin) rather than a flat
 *   backstop. An open-ended run has no natural end, so one un-paused stretch is capped at
 *   [OPEN_ENDED_MAX_SEGMENT_MS] and then **auto-paused** (never left "running" without its lock).
 */
@Singleton
class CardioRunController internal constructor(
    private val context: Context,
    private val repository: CardioRepository,
    private val time: TimeProvider,
    private val scope: CoroutineScope,
    private val wakeLock: WakeLockHolder,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        repository: CardioRepository,
        time: TimeProvider,
        @ApplicationScope scope: CoroutineScope,
    ) : this(
        context, repository, time, scope,
        WakeLockHolder(context, WAKE_LOCK_TAG, OPEN_ENDED_MAX_SEGMENT_MS + WAKELOCK_MARGIN_MS),
    )

    private val _state = MutableStateFlow<CardioRunState?>(null)
    val state: StateFlow<CardioRunState?> = _state.asStateFlow()

    private var plan: RunPlan? = null
    private var sessionLocalId: String? = null
    private var runJob: Job? = null

    /** The single delayed "left paused too long" exit. Armed by [pause], disarmed by everything else. */
    private var pausedIdleJob: Job? = null

    /** The local id of the session this run is logging to, for notification deep-links. */
    val activeSessionId: String? get() = sessionLocalId

    // Monotonic timing baseline.
    private var accumulatedMs: Long = 0L
    private var segmentStartRealtime: Long = 0L
    private var paused: Boolean = false
    private var complete: Boolean = false

    private var lastCuedIndex: Int = -1
    private var lastPersistSec: Int = -1

    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false

    // -- start paths --------------------------------------------------------

    fun startGuided(
        programId: String,
        week: Int,
        day: Int,
        intervals: List<Interval>,
        label: String,
        weekDayLabel: String,
        resume: CardioSessionEntity? = null,
    ) {
        startRun(
            RunPlan(programId, week, day, intervals, openEnded = false, label, weekDayLabel),
            resume,
        )
    }

    fun startFree(openEnded: Boolean, intervals: List<Interval>, resume: CardioSessionEntity? = null) {
        val effective = if (openEnded) listOf(Interval(CardioPhase.RUN, 0)) else intervals
        startRun(
            RunPlan(
                programId = CardioPrograms.FREE_RUN_ID,
                week = null,
                day = null,
                intervals = effective,
                openEnded = openEnded,
                label = "Free Run",
                weekDayLabel = null,
            ),
            resume = resume,
        )
    }

    @Synchronized
    private fun startRun(plan: RunPlan, resume: CardioSessionEntity?) {
        runJob?.cancel()
        pausedIdleJob?.cancel()
        this.plan = plan
        sessionLocalId = resume?.id
        val startElapsed = resume?.totalElapsedSec ?: 0
        accumulatedMs = startElapsed * 1000L
        segmentStartRealtime = time.elapsedRealtimeMs()
        paused = false
        complete = false
        lastCuedIndex = -1
        lastPersistSec = startElapsed
        emit(elapsedSec = startElapsed)
        initTts()
        acquireWakeLock(plan, startElapsed)
        CardioRunService.start(context)
        launchTickLoop(plan)
    }

    /**
     * (Re)launch the tick loop — on start and on every [resume]. The session row is created on the
     * first launch only (a resumed or restored run already has its id).
     */
    private fun launchTickLoop(plan: RunPlan) {
        runJob = scope.launch {
            if (sessionLocalId == null) {
                // NonCancellable: a Pause landing mid-create (it awaits the server) must not lose
                // the new row's id, or the run would have nothing to persist to.
                val entity = withContext(NonCancellable) {
                    try {
                        repository.startSession(plan.programId, plan.week, plan.day)
                    } catch (_: Exception) {
                        null
                    }
                }
                sessionLocalId = entity?.id
                if (paused && !complete) persistNow()
            }
            tickLoop()
        }
    }

    private suspend fun tickLoop() {
        while (currentCoroutineContext().isActive && !complete && !paused) {
            val elapsed = currentElapsedSec()
            emit(elapsed)
            maybeCue()
            maybePersist(elapsed)
            val p = plan
            if (p != null && !p.openEnded && elapsed >= totalDurationSec(p)) {
                finalizeComplete(elapsed)
                break
            }
            if (p != null && p.openEnded &&
                time.elapsedRealtimeMs() - segmentStartRealtime >= OPEN_ENDED_MAX_SEGMENT_MS
            ) {
                // A forgotten Free Run: stop burning the CPU, but keep the run (persisted, resumable)
                // rather than letting the wake-lock time out underneath a "running" clock.
                pause()
                break
            }
            delay(TICK_MS)
        }
    }

    /**
     * Hold the CPU for as long as this plan can legitimately keep running from [elapsedSec]: the
     * remaining plan time for a guided/interval run, one capped stretch for an open-ended one —
     * plus a margin so the loop (which completes or auto-pauses the run) always beats the timeout.
     */
    private fun acquireWakeLock(p: RunPlan, elapsedSec: Int) {
        wakeLock.acquire(wakeLockTimeoutMs(p.openEnded, totalDurationSec(p), elapsedSec))
    }

    // -- controls -----------------------------------------------------------

    /**
     * Pause: freeze the clock, then stop the loop and drop the wake-lock so a paused run costs
     * nothing with the phone locked. The foreground service stays up; [PAUSED_IDLE_EXIT_MS] later a
     * still-paused run exits itself. That timer is one delayed coroutine — it holds no wake-lock, so
     * in doze it simply fires late, which is fine (a suspended device isn't spending anything on us).
     */
    @Synchronized
    fun pause() {
        if (paused || complete || _state.value == null) return
        accumulatedMs = rawElapsedMs()
        paused = true
        runJob?.cancel()
        wakeLock.release()
        emit(currentElapsedSec())
        persistNow()
        pausedIdleJob?.cancel()
        pausedIdleJob = scope.launch {
            delay(PAUSED_IDLE_EXIT_MS)
            exitIfStillPaused()
        }
    }

    @Synchronized
    fun resume() {
        if (!paused || complete || _state.value == null) return
        val p = plan ?: return
        pausedIdleJob?.cancel()
        segmentStartRealtime = time.elapsedRealtimeMs()
        paused = false
        val elapsed = currentElapsedSec()
        emit(elapsed)
        acquireWakeLock(p, elapsed)
        // Idempotent insurance: the service normally outlives a pause; this covers it having died.
        CardioRunService.start(context)
        launchTickLoop(p)
    }

    @Synchronized
    private fun exitIfStillPaused() {
        // A resume that raced the delay wins (its cancel may land after this coroutine woke).
        if (paused && !complete && _state.value != null) pauseAndExit()
    }

    @Synchronized
    fun skipWarmup() {
        val p = plan ?: return
        if (p.openEnded) return
        val first = p.intervals.firstOrNull() ?: return
        if (first.phase != CardioPhase.WARM_UP) return
        if (currentElapsedSec() >= first.durationSec) return
        setElapsed(first.durationSec)
        emit(first.durationSec)
        persistNow()
    }

    /** Leave the run screen without finishing — the session stays in progress and is resumable. */
    @Synchronized
    fun pauseAndExit() {
        if (!complete) {
            if (!paused) {
                accumulatedMs = rawElapsedMs()
                paused = true
            }
            persistNow()
        }
        runJob?.cancel()
        pausedIdleJob?.cancel()
        wakeLock.release()
        CardioRunService.stop(context)
        _state.value = null
        releaseTts()
    }

    /** Finish the run now (counts as completed). */
    @Synchronized
    fun finish() {
        val elapsed = currentElapsedSec()
        finalizeComplete(elapsed)
    }

    /** Acknowledge a finished run; clears the live state. */
    fun clear() {
        _state.value = null
    }

    @Synchronized
    private fun finalizeComplete(elapsedSec: Int) {
        complete = true
        paused = true
        accumulatedMs = elapsedSec * 1000L
        runJob?.cancel()
        pausedIdleJob?.cancel()
        wakeLock.release()
        cue("Workout complete. Great job.")
        val id = sessionLocalId
        scope.launch {
            if (id != null) {
                try {
                    repository.completeSession(id, elapsedSec)
                } catch (_: Exception) {
                }
            }
            CardioRunService.stop(context)
        }
        val p = plan
        if (p != null) {
            _state.value = deriveState(p, elapsedSec).copy(isComplete = true, isPaused = true)
        }
        releaseTts()
    }

    // -- timing helpers -----------------------------------------------------

    private fun rawElapsedMs(): Long =
        accumulatedMs + if (!paused) (time.elapsedRealtimeMs() - segmentStartRealtime) else 0L

    private fun currentElapsedSec(): Int = (rawElapsedMs() / 1000L).toInt()

    private fun setElapsed(sec: Int) {
        accumulatedMs = sec * 1000L
        segmentStartRealtime = time.elapsedRealtimeMs()
    }

    private fun totalDurationSec(p: RunPlan): Int =
        if (p.openEnded) 0 else p.intervals.sumOf { it.durationSec }

    private fun emit(elapsedSec: Int) {
        val p = plan ?: return
        _state.value = deriveState(p, elapsedSec)
    }

    private fun deriveState(p: RunPlan, elapsedSec: Int): CardioRunState {
        if (p.openEnded) {
            return CardioRunState(
                intervals = p.intervals,
                currentIndex = 0,
                phase = CardioPhase.RUN,
                intervalElapsedSec = elapsedSec,
                intervalRemainingSec = -1,
                intervalDurationSec = 0,
                totalElapsedSec = elapsedSec,
                totalDurationSec = 0,
                isPaused = paused,
                isComplete = complete,
                isOpenEnded = true,
                label = p.label,
                weekDayLabel = p.weekDayLabel,
            )
        }
        val total = totalDurationSec(p)
        var acc = 0
        var index = p.intervals.lastIndex
        var withinElapsed = 0
        var duration = p.intervals.lastOrNull()?.durationSec ?: 0
        for (i in p.intervals.indices) {
            val iv = p.intervals[i]
            if (elapsedSec < acc + iv.durationSec) {
                index = i
                withinElapsed = elapsedSec - acc
                duration = iv.durationSec
                break
            }
            acc += iv.durationSec
            if (i == p.intervals.lastIndex) {
                // Past the end.
                index = i
                withinElapsed = iv.durationSec
                duration = iv.durationSec
            }
        }
        val phase = p.intervals[index].phase
        val remaining = (duration - withinElapsed).coerceAtLeast(0)
        return CardioRunState(
            intervals = p.intervals,
            currentIndex = index,
            phase = phase,
            intervalElapsedSec = withinElapsed,
            intervalRemainingSec = remaining,
            intervalDurationSec = duration,
            totalElapsedSec = elapsedSec.coerceAtMost(total),
            totalDurationSec = total,
            isPaused = paused,
            isComplete = complete,
            isOpenEnded = false,
            label = p.label,
            weekDayLabel = p.weekDayLabel,
        )
    }

    // -- persistence --------------------------------------------------------

    private fun maybePersist(elapsedSec: Int) {
        if (elapsedSec - lastPersistSec >= PERSIST_EVERY_SEC) {
            lastPersistSec = elapsedSec
            persist(elapsedSec)
        }
    }

    private fun persistNow() {
        val elapsed = currentElapsedSec()
        lastPersistSec = elapsed
        persist(elapsed)
    }

    private fun persist(elapsedSec: Int) {
        val id = sessionLocalId ?: return
        scope.launch {
            try {
                repository.updateProgress(id, elapsedSec)
            } catch (_: Exception) {
            }
        }
    }

    // -- cues ---------------------------------------------------------------

    private fun maybeCue() {
        val s = _state.value ?: return
        if (s.isOpenEnded) return
        if (s.currentIndex != lastCuedIndex) {
            val first = lastCuedIndex == -1
            lastCuedIndex = s.currentIndex
            if (!first) {
                vibrate()
                cue("${s.phase.label} for ${spoken(s.intervalDurationSec)}")
            }
        }
    }

    private fun spoken(sec: Int): String {
        val m = sec / 60
        val s = sec % 60
        return when {
            m > 0 && s == 0 -> "$m minute${if (m == 1) "" else "s"}"
            m > 0 -> "$m minute${if (m == 1) "" else "s"} $s seconds"
            else -> "$s seconds"
        }
    }

    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(450, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    private fun initTts() {
        if (tts != null) return
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
            }
        } catch (_: Exception) {
            tts = null
        }
    }

    private fun cue(text: String) {
        try {
            if (ttsReady) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cardio_cue")
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseTts() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
    }

    companion object {
        private const val TICK_MS = 200L
        private const val PERSIST_EVERY_SEC = 15
        private const val WAKE_LOCK_TAG = "spotter:cardio_run"

        /** Slack on top of the expected run time so the loop always ends the run before the lock does. */
        const val WAKELOCK_MARGIN_MS = 5L * 60 * 1000

        /**
         * The longest single un-paused stretch of an open-ended run. Nothing else ever ends a Free
         * Run, so a forgotten one is auto-paused here (resuming starts a fresh stretch).
         */
        const val OPEN_ENDED_MAX_SEGMENT_MS = 3L * 60 * 60 * 1000

        /** A run left paused this long exits itself (persisted + resumable), stopping the service. */
        const val PAUSED_IDLE_EXIT_MS = 30L * 60 * 1000

        /** Wake-lock leak guard for a run at [elapsedSec]. Pure for testing. */
        fun wakeLockTimeoutMs(openEnded: Boolean, totalDurationSec: Int, elapsedSec: Int): Long =
            if (openEnded) {
                OPEN_ENDED_MAX_SEGMENT_MS + WAKELOCK_MARGIN_MS
            } else {
                (totalDurationSec - elapsedSec).coerceAtLeast(0) * 1000L + WAKELOCK_MARGIN_MS
            }
    }
}
