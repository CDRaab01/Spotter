package com.spotter.util

import com.spotter.data.local.dao.CardioSessionDao
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.ui.cardio.CardioFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes today's in-progress cardio session straight from Room so the app shell can show a
 * "resume" banner anywhere in the app — the cardio counterpart to [ActiveWorkoutStore]. Being
 * DB-derived it survives process death and clears itself when the run is finished or abandoned;
 * the today-filter (on the ISO `startedAt`) keeps stale sessions from past days from resurfacing.
 *
 * Note: a paused run the user has left (`CardioRunController.pauseAndExit`) is no longer live in
 * the controller but stays `in_progress` here, which is exactly what makes it resumable.
 */
@Singleton
class ActiveCardioStore @Inject constructor(
    dao: CardioSessionDao,
) {
    val activeCardio: Flow<CardioSessionEntity?> = dao.observeInProgress().map { sessions ->
        val today = LocalDate.now()
        sessions.firstOrNull { CardioFormat.parseDate(it.startedAt) == today }
    }
}
