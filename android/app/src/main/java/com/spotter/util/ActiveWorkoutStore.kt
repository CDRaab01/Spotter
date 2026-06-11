package com.spotter.util

import com.spotter.data.local.dao.WorkoutSessionDao
import com.spotter.data.local.entity.WorkoutSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes today's in-progress workout straight from Room so the app shell can show a "resume"
 * strip anywhere in the app. Being DB-derived it survives process death and clears itself when
 * the session is finished or deleted; the today-filter keeps stale abandoned sessions from past
 * days (or server-synced ones) from resurrecting the strip.
 */
@Singleton
class ActiveWorkoutStore @Inject constructor(
    sessionDao: WorkoutSessionDao,
) {
    val activeSession: Flow<WorkoutSessionEntity?> = sessionDao.observeAll().map { sessions ->
        val today = LocalDate.now().toString()
        sessions.firstOrNull { it.status == "in_progress" && it.date == today }
    }
}
