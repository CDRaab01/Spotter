package com.spotter.health

import com.spotter.data.local.dao.WorkoutRoutineDao
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.local.entity.WorkoutSessionEntity
import com.spotter.data.model.CardioStatus
import com.spotter.util.AppPreferences
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The write side of the Health Connect mirror, expressed as *events the repositories already emit*
 * so the hook at each save site is a single line and nothing else in `data/repository/` moves.
 *
 * Every "did this just complete?" decision lives here rather than at the call site, and every
 * implementation is best-effort: a disabled toggle, a missing SDK, a denied permission or a
 * platform error is swallowed, because a health-mirror failure must never affect the user's save.
 *
 * [NoOp] is the constructor default in the repositories, so unit tests construct them exactly as
 * before; Hilt injects the real [HealthConnectSync] in the app.
 */
interface HealthSync {

    /**
     * A strength session was saved. Mirrors only the transition *into* `completed`, so re-saving a
     * finished workout (e.g. an edited note) can't duplicate the record.
     */
    suspend fun onStrengthSessionSaved(previousStatus: String?, saved: WorkoutSessionEntity)

    /** A cardio session was saved. Mirrors only the transition into [CardioStatus.COMPLETED]. */
    suspend fun onCardioSessionSaved(previous: CardioSessionEntity?, saved: CardioSessionEntity)

    /** A weigh-in was logged. [weightLb] is Spotter's canonical bodyweight unit. */
    suspend fun onBodyweightLogged(date: String, weightLb: Double)

    /** Does nothing. The default wherever Health Connect isn't wired (unit tests, previews). */
    object NoOp : HealthSync {
        override suspend fun onStrengthSessionSaved(previousStatus: String?, saved: WorkoutSessionEntity) = Unit
        override suspend fun onCardioSessionSaved(previous: CardioSessionEntity?, saved: CardioSessionEntity) = Unit
        override suspend fun onBodyweightLogged(date: String, weightLb: Double) = Unit
    }

    companion object {
        /** The status string a finished strength session carries. */
        const val STATUS_COMPLETED = "completed"
    }
}

/**
 * Health Connect-backed [HealthSync]. Gated on the opt-in
 * [AppPreferences.healthConnectEnabled] toggle **and** a live permission check, both re-read at
 * write time so a revoked permission or a flipped toggle takes effect immediately.
 */
@Singleton
class HealthConnectSync @Inject constructor(
    private val manager: HealthConnectManager,
    private val appPreferences: AppPreferences,
    private val routineDao: WorkoutRoutineDao,
) : HealthSync {

    override suspend fun onStrengthSessionSaved(previousStatus: String?, saved: WorkoutSessionEntity) {
        if (saved.status != HealthSync.STATUS_COMPLETED) return
        if (previousStatus == HealthSync.STATUS_COMPLETED) return
        if (!enabled()) return
        val routineName = saved.routineId
            ?.let { runCatching { routineDao.getById(it) }.getOrNull() }
            ?.name
        val input = HealthMapper.strengthSession(
            startedAtMs = saved.startedAtMs,
            date = saved.date,
            durationSeconds = saved.durationSeconds,
            routineName = routineName,
        ) ?: return
        manager.writeSession(input)
    }

    override suspend fun onCardioSessionSaved(previous: CardioSessionEntity?, saved: CardioSessionEntity) {
        if (saved.status != CardioStatus.COMPLETED) return
        if (previous?.status == CardioStatus.COMPLETED) return
        if (!enabled()) return
        val input = HealthMapper.cardioSession(
            startedAt = saved.startedAt,
            completedAt = saved.completedAt,
            totalElapsedSec = saved.totalElapsedSec,
            activityType = saved.activityType,
        ) ?: return
        manager.writeSession(input)
    }

    override suspend fun onBodyweightLogged(date: String, weightLb: Double) {
        if (!enabled()) return
        val input = HealthMapper.bodyweight(date = date, weightLb = weightLb) ?: return
        manager.writeWeight(input)
    }

    /** Opt-in toggle on, SDK present, and both write permissions still granted. */
    private suspend fun enabled(): Boolean = runCatching {
        appPreferences.healthConnectEnabled.first() &&
            manager.isAvailable &&
            manager.hasPermissions()
    }.getOrDefault(false)
}
