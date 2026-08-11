package com.spotter.util.nudge

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
            NudgeNotifications.post(applicationContext, NOTIFICATION_ID, decision.title, decision.text)
        }
        return Result.success()
    }

    private suspend fun evaluate(): WorkoutNudge.Decision {
        val enabled = appPreferences.workoutNudgeEnabled.first()
        val notificationsAllowed = NotificationManagerCompat.from(applicationContext)
            .areNotificationsEnabled()
        // Quiet hours are user-set to the minute, so compare at minute resolution.
        val quietStart = appPreferences.quietStartTime.first()
        val quietEnd = appPreferences.quietEndTime.first()
        val now = LocalTime.now()
        val nowMinuteOfDay = now.hour * 60 + now.minute

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
            nowMinuteOfDay = nowMinuteOfDay,
            quietStartMinuteOfDay = quietStart.minuteOfDay,
            quietEndMinuteOfDay = quietEnd.minuteOfDay,
            isWorkoutDayToday = isWorkoutDayToday,
            alreadyTrainedToday = alreadyTrainedToday,
            dayLabel = dayLabel,
            routineName = routineName,
        )
    }

    companion object {
        const val CHANNEL_ID = NudgeNotifications.CHANNEL_ID
        const val NOTIFICATION_ID = 1003
    }
}
