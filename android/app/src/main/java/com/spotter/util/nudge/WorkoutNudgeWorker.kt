package com.spotter.util.nudge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spotter.MainActivity
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.util.AppPreferences
import com.spotter.util.ProjectionDay
import com.spotter.util.SessionAnchor
import com.spotter.util.WorkoutProjection
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

/**
 * The workout-morning nudge (Tier W2b). Runs ~8:00 on a daily [WorkoutNudgeScheduler] schedule and,
 * only when the user opted in and today is genuinely a scheduled workout day, posts a single local
 * notification ("Push day today — ready when you are."). All the "should we?" rules live in the pure
 * [WorkoutNudge.decide]; this worker just gathers the inputs from Room + prefs and posts.
 *
 * It re-derives today's schedule the same way [com.spotter.widget.WidgetUpdater] and Home do (active
 * program → projected slot), so it stays in step with what the user sees. Client-side only — no
 * server call. Never fires on rest days, when nothing is scheduled, when a session already exists
 * today, or when notifications are denied.
 */
@HiltWorker
class WorkoutNudgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val appPreferences: AppPreferences,
    private val programDao: WorkoutProgramDao,
    private val programDayDao: ProgramDayDao,
    private val sessionDao: WorkoutSessionDao,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val decision = runCatching { evaluate() }.getOrElse { return Result.success() }
        if (decision is WorkoutNudge.Decision.Show) {
            postNotification(decision.title, decision.text)
        }
        return Result.success()
    }

    private suspend fun evaluate(): WorkoutNudge.Decision {
        val enabled = appPreferences.workoutNudgeEnabled.first()
        val notificationsAllowed = NotificationManagerCompat.from(applicationContext)
            .areNotificationsEnabled()
        val quietStart = appPreferences.quietStartHour.first()
        val quietEnd = appPreferences.quietEndHour.first()
        val nowHour = LocalTime.now().hour

        // Determine today's scheduled slot from the active program (mirrors WidgetUpdater.buildData).
        val today = LocalDate.now()
        val todayStr = today.toString()
        val sessions = sessionDao.getAll()
        val alreadyTrainedToday = sessions.any {
            it.date == todayStr && (it.status == "completed" || it.status == "in_progress")
        }

        var isWorkoutDayToday = false
        var dayLabel: String? = null
        var routineName: String? = null

        val program = programDao.getActive()
        if (program != null) {
            val days = programDayDao.getByProgram(program.id)
                .map { ProjectionDay(it.routineId, it.label, it.routineName) }
            if (days.isNotEmpty()) {
                val cadence = appPreferences.workoutCadenceDays.first()
                val anchor = sessions
                    .filter { it.status == "completed" || it.status == "in_progress" }
                    .mapNotNull { s ->
                        runCatching { LocalDate.parse(s.date) }.getOrNull()
                            ?.let { SessionAnchor(it, s.routineId, s.status) }
                    }
                    .maxByOrNull { it.date }
                val slot = WorkoutProjection.project(today, cadence, anchor, days, count = 1).firstOrNull()
                if (slot != null && slot.date == today && slot.routineId != null) {
                    isWorkoutDayToday = true
                    dayLabel = slot.label
                    routineName = slot.routineName
                }
            }
        }

        return WorkoutNudge.decide(
            enabled = enabled,
            notificationsAllowed = notificationsAllowed,
            nowHour = nowHour,
            quietStartHour = quietStart,
            quietEndHour = quietEnd,
            isWorkoutDayToday = isWorkoutDayToday,
            alreadyTrainedToday = alreadyTrainedToday,
            dayLabel = dayLabel,
            routineName = routineName,
        )
    }

    private fun postNotification(title: String, text: String) {
        ensureChannel()
        val tap = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        // areNotificationsEnabled() was checked in evaluate(); guard again for lint/race safety.
        val manager = NotificationManagerCompat.from(applicationContext)
        if (manager.areNotificationsEnabled()) {
            runCatching { manager.notify(NOTIFICATION_ID, notification) }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext
                .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Workout reminders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Morning nudge on days you have a workout scheduled" }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "spotter_nudge"
        const val NOTIFICATION_ID = 1003
    }
}
