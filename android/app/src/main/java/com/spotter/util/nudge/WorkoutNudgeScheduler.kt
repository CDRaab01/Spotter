package com.spotter.util.nudge

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules (or cancels) the once-a-day [WorkoutNudgeWorker]. Driven by the opt-in preference from
 * [com.spotter.SpotterApp] on start and by the Settings toggle. The work is a daily periodic task
 * whose initial delay lands on the next local [targetHour]; the worker itself re-checks every guard
 * (enabled, permission, quiet hours, is-today-a-workout-day) so a stale schedule can never nag.
 */
@Singleton
class WorkoutNudgeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Enqueue the daily nudge when [enabled], otherwise cancel any existing schedule. */
    fun sync(enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val delayMs = nextRunDelayMillis(ZonedDateTime.now(), targetHour = TARGET_HOUR)
        val request = PeriodicWorkRequestBuilder<WorkoutNudgeWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        // KEEP so an already-scheduled daily nudge isn't reset to a fresh 24h window on every launch.
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val WORK_NAME = "workout_morning_nudge"

        /** Local hour the nudge should fire (~8:00). */
        val TARGET_HOUR: Int = com.spotter.util.AppPreferences.NUDGE_HOUR

        /**
         * Milliseconds from [now] until the next occurrence of [targetHour]:00 local time. If it is
         * already past [targetHour] today, returns the delay to [targetHour] tomorrow. Pure, so the
         * math is unit-testable.
         */
        fun nextRunDelayMillis(now: ZonedDateTime, targetHour: Int): Long {
            val zone: ZoneId = now.zone
            val todayTarget = LocalDate.from(now)
                .atTime(LocalTime.of(targetHour, 0))
                .atZone(zone)
            val next = if (now.isBefore(todayTarget)) todayTarget else todayTarget.plusDays(1)
            return java.time.Duration.between(now, next).toMillis().coerceAtLeast(0)
        }
    }
}
