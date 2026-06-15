package com.spotter.ui.cardio

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.model.CardioPhase
import com.spotter.data.model.Interval
import com.spotter.data.repository.CardioRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

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
 * The drift-free cardio timer. Time is measured from [SystemClock.elapsedRealtime] deltas, not a
 * tick counter, so coalesced ticks or a backgrounded screen never accumulate error — the displayed
 * time is always recomputed from a monotonic baseline. The loop lives in an app-scoped coroutine
 * and is kept alive in the background by [CardioRunService] (a foreground service), so cues still
 * fire and progress still persists while the phone is locked.
 */
@Singleton
class CardioRunController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CardioRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<CardioRunState?>(null)
    val state: StateFlow<CardioRunState?> = _state.asStateFlow()

    private var plan: RunPlan? = null
    private var sessionLocalId: String? = null
    private var runJob: Job? = null

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

    private fun startRun(plan: RunPlan, resume: CardioSessionEntity?) {
        runJob?.cancel()
        this.plan = plan
        sessionLocalId = resume?.id
        val startElapsed = resume?.totalElapsedSec ?: 0
        accumulatedMs = startElapsed * 1000L
        segmentStartRealtime = SystemClock.elapsedRealtime()
        paused = false
        complete = false
        lastCuedIndex = -1
        lastPersistSec = startElapsed
        emit(elapsedSec = startElapsed)
        initTts()
        CardioRunService.start(context)
        runJob = scope.launch {
            if (sessionLocalId == null) {
                val entity = try {
                    repository.startSession(plan.programId, plan.week, plan.day)
                } catch (_: Exception) {
                    null
                }
                sessionLocalId = entity?.id
            }
            tickLoop()
        }
    }

    private suspend fun tickLoop() {
        while (scope.isActive && !complete) {
            val elapsed = currentElapsedSec()
            emit(elapsed)
            maybeCue()
            maybePersist(elapsed)
            val p = plan
            if (p != null && !p.openEnded && elapsed >= totalDurationSec(p)) {
                finalizeComplete(elapsed)
                break
            }
            delay(TICK_MS)
        }
    }

    // -- controls -----------------------------------------------------------

    fun pause() {
        if (paused || complete) return
        accumulatedMs = rawElapsedMs()
        paused = true
        emit(currentElapsedSec())
        persistNow()
    }

    fun resume() {
        if (!paused || complete) return
        segmentStartRealtime = SystemClock.elapsedRealtime()
        paused = false
        emit(currentElapsedSec())
    }

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
    fun pauseAndExit() {
        if (!complete) {
            if (!paused) {
                accumulatedMs = rawElapsedMs()
                paused = true
            }
            persistNow()
        }
        runJob?.cancel()
        CardioRunService.stop(context)
        _state.value = null
    }

    /** Finish the run now (counts as completed). */
    fun finish() {
        val elapsed = currentElapsedSec()
        finalizeComplete(elapsed)
    }

    /** Acknowledge a finished run; clears the live state. */
    fun clear() {
        _state.value = null
    }

    private fun finalizeComplete(elapsedSec: Int) {
        complete = true
        paused = true
        accumulatedMs = elapsedSec * 1000L
        runJob?.cancel()
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
        accumulatedMs + if (!paused) (SystemClock.elapsedRealtime() - segmentStartRealtime) else 0L

    private fun currentElapsedSec(): Int = (rawElapsedMs() / 1000L).toInt()

    private fun setElapsed(sec: Int) {
        accumulatedMs = sec * 1000L
        segmentStartRealtime = SystemClock.elapsedRealtime()
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
    }
}
