package com.spotter.util.nudge

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.spotter.util.AppPreferences
import com.spotter.util.TimeOfDay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules (or cancels) the daily nudge workers — the morning [WorkoutNudgeWorker] and the evening
 * [EveningNudgeWorker] (streak-saver/comeback) — behind the single opt-in preference, driven from
 * [com.spotter.SpotterApp] on start and by the Settings toggle. Each work is a daily periodic task
 * whose initial delay lands on the next occurrence of its configured local time; the workers
 * themselves re-check every guard (enabled, permission, quiet hours, schedule state) at fire time
 * so a stale schedule can never nag.
 */
@Singleton
class WorkoutNudgeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appPreferences: AppPreferences,
) {
    /**
     * Enqueue the daily nudges when [enabled], otherwise cancel any existing schedule.
     *
     * The times are user-settable, which is why this can't just enqueue with `KEEP`: KEEP treats an
     * already-scheduled work as satisfying the request and silently drops the new initial delay, so
     * moving the reminder from 8:00 to 7:00 would appear to work and then fire at 8:00 forever.
     * Instead the requested schedule is reduced to a signature: unchanged ⇒ enqueue with KEEP (a
     * no-op that still re-creates the work if the system dropped it, and crucially does *not* reset
     * the running 24h window on every app launch); changed ⇒ CANCEL_AND_REENQUEUE so the new time
     * actually takes effect.
     */
    suspend fun sync(
        enabled: Boolean,
        morning: TimeOfDay = TimeOfDay(AppPreferences.NUDGE_HOUR),
        evening: TimeOfDay = TimeOfDay(AppPreferences.EVENING_NUDGE_HOUR),
    ) {
        val wm = WorkManager.getInstance(context)
        val signature = signatureOf(enabled, morning, evening)
        val previous = runCatching { appPreferences.nudgeScheduleSignature.first() }.getOrNull()

        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(EVENING_WORK_NAME)
            if (previous != signature) {
                runCatching { appPreferences.setNudgeScheduleSignature(signature) }
            }
            return
        }

        val policy = if (previous == signature) {
            ExistingPeriodicWorkPolicy.KEEP
        } else {
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
        }
        val now = ZonedDateTime.now()
        val morningWork = PeriodicWorkRequestBuilder<WorkoutNudgeWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(
                nextRunDelayMillis(now, morning.hour, morning.minute),
                TimeUnit.MILLISECONDS,
            )
            .build()
        val eveningWork = PeriodicWorkRequestBuilder<EveningNudgeWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(
                nextRunDelayMillis(now, evening.hour, evening.minute),
                TimeUnit.MILLISECONDS,
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, policy, morningWork)
        wm.enqueueUniquePeriodicWork(EVENING_WORK_NAME, policy, eveningWork)
        if (previous != signature) {
            runCatching { appPreferences.setNudgeScheduleSignature(signature) }
        }
    }

    companion object {
        const val WORK_NAME = "workout_morning_nudge"
        const val EVENING_WORK_NAME = "workout_evening_nudge"

        /** The schedule reduced to a comparable string — see [sync] for why this exists. */
        fun signatureOf(enabled: Boolean, morning: TimeOfDay, evening: TimeOfDay): String =
            "$enabled|${morning.hour}:${morning.minute}|${evening.hour}:${evening.minute}"

        /**
         * Milliseconds from [now] until the next occurrence of [targetHour]:[targetMinute] local
         * time. If that moment has already passed today, returns the delay to tomorrow's. Pure, so
         * the math is unit-testable.
         */
        fun nextRunDelayMillis(now: ZonedDateTime, targetHour: Int, targetMinute: Int = 0): Long {
            val zone: ZoneId = now.zone
            val todayTarget = LocalDate.from(now)
                .atTime(LocalTime.of(targetHour, targetMinute))
                .atZone(zone)
            val next = if (now.isBefore(todayTarget)) todayTarget else todayTarget.plusDays(1)
            return java.time.Duration.between(now, next).toMillis().coerceAtLeast(0)
        }
    }
}
