package com.spotter.util.nudge

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.spotter.data.local.dao.CardioSessionDao
import com.spotter.data.local.dao.ProgramDayDao
import com.spotter.data.local.dao.WorkoutProgramDao
import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.ui.cardio.CardioFormat
import com.spotter.util.AppPreferences
import com.spotter.util.ProjectionDay
import com.spotter.util.SessionAnchor
import com.spotter.util.StreakCalculator
import com.spotter.util.WorkoutProjection
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

/**
 * The evening motivation nudge (~18:00 daily via [WorkoutNudgeScheduler]). Evaluates two
 * mutually-exclusive kinds through the pure [EveningNudge.decide]:
 *
 *  - **Streak-saver**: today is a scheduled workout day, nothing logged yet, and there is a
 *    live streak to lose — "Your N-day streak is on the line."
 *  - **Comeback**: 2–3 scheduled workout days already missed — one gentle re-engagement,
 *    latched per miss episode (see [AppPreferences.comebackNudgeAnchor]) so it can never
 *    fire twice for the same lapse.
 *
 * Gathers its inputs the same way Home does (completed strength ∪ cardio dates, rest days
 * from the projection, streak via [StreakCalculator]) so the number in the notification is
 * the number on the Home screen. Client-side only; requires an active program.
 */
@HiltWorker
class EveningNudgeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val appPreferences: AppPreferences,
    private val programDao: WorkoutProgramDao,
    private val programDayDao: ProgramDayDao,
    private val sessionDao: WorkoutSessionDao,
    private val cardioSessionDao: CardioSessionDao,
) : CoroutineWorker(appContext, params) {

    /** Set by [evaluate]; identifies the miss episode a posted comeback nudge latches. */
    private var lastCompletedDate: LocalDate? = null

    override suspend fun doWork(): Result {
        val decision = runCatching { evaluate() }.getOrElse { return Result.success() }
        if (decision is EveningNudge.Decision.Show) {
            NudgeNotifications.post(
                applicationContext, decision.kind.notificationId, decision.title, decision.text,
            )
            if (decision.kind == EveningNudge.Kind.COMEBACK) {
                // Latch this miss episode so the comeback fires once per lapse.
                lastCompletedDate?.let {
                    runCatching { appPreferences.setComebackNudgeAnchor(it.toString()) }
                }
            }
        }
        return Result.success()
    }

    private suspend fun evaluate(): EveningNudge.Decision {
        val enabled = appPreferences.workoutNudgeEnabled.first()
        val notificationsAllowed = NotificationManagerCompat.from(applicationContext)
            .areNotificationsEnabled()
        val quietStart = appPreferences.quietStartHour.first()
        val quietEnd = appPreferences.quietEndHour.first()
        val nowHour = LocalTime.now().hour
        val today = LocalDate.now()

        val sessions = sessionDao.getAll()
        val completedStrengthDates = sessions
            .filter { it.status == "completed" }
            .mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        val completedCardioDates = runCatching {
            cardioSessionDao.observeAll().first()
                .filter { it.status == "completed" }
                .mapNotNull { CardioFormat.parseDate(it.completedAt) }
        }.getOrDefault(emptyList())
        val completedDates = (completedStrengthDates + completedCardioDates).toSet()

        val todayStr = today.toString()
        val trainedToday = completedDates.contains(today) ||
            sessions.any { it.date == todayStr && it.status == "in_progress" }

        var isWorkoutDayToday = false
        var cadenceStep = 1
        var restDayDates: Set<LocalDate> = emptySet()
        var hasActiveProgram = false

        val program = programDao.getActive()
        if (program != null) {
            val days = programDayDao.getByProgram(program.id)
                .map { ProjectionDay(it.routineId, it.label, it.routineName) }
            if (days.isNotEmpty()) {
                hasActiveProgram = true
                val cadence = appPreferences.workoutCadenceDays.first()
                cadenceStep = WorkoutProjection.effectiveCadence(cadence, days)
                val anchor = sessions
                    .filter { it.status == "completed" || it.status == "in_progress" }
                    .mapNotNull { s ->
                        runCatching { LocalDate.parse(s.date) }.getOrNull()
                            ?.let { SessionAnchor(it, s.routineId, s.status) }
                    }
                    .maxByOrNull { it.date }
                restDayDates = WorkoutProjection.restDayDatesInRange(
                    anchor, days, today.minusDays(90), today,
                )
                val slot = WorkoutProjection.project(today, cadence, anchor, days, count = 1)
                    .firstOrNull()
                isWorkoutDayToday = slot != null && slot.date == today && slot.routineId != null
            }
        }

        val currentStreak = StreakCalculator.currentStreak(today, completedDates, restDayDates)
        val lastCompleted = StreakCalculator.lastCompletedDate(completedDates)
        lastCompletedDate = lastCompleted
        // No active program ⇒ nothing is scheduled, so nothing can be "missed".
        val consecutiveMissed = if (hasActiveProgram) {
            StreakCalculator.consecutiveMissedWorkoutDays(
                today, completedDates, restDayDates, cadenceStep, lastCompleted,
            )
        } else {
            0
        }
        val alreadyNudged = lastCompleted != null &&
            appPreferences.comebackNudgeAnchor.first() == lastCompleted.toString()

        return EveningNudge.decide(
            enabled = enabled,
            notificationsAllowed = notificationsAllowed,
            nowHour = nowHour,
            quietStartHour = quietStart,
            quietEndHour = quietEnd,
            isWorkoutDayToday = isWorkoutDayToday,
            trainedToday = trainedToday,
            currentStreak = currentStreak,
            consecutiveMissedDays = consecutiveMissed,
            alreadyNudgedThisEpisode = alreadyNudged,
            today = today,
        )
    }

}
