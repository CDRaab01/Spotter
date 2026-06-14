package com.spotter.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.dao.SetLogDao
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.repository.CardioRepository
import com.spotter.ui.cardio.CardioFormat
import com.spotter.ui.cardio.CardioPrograms
import com.spotter.ui.cardio.CardioRunController
import com.spotter.util.ActiveCardioStore
import com.spotter.util.ActiveWorkoutStore
import com.spotter.util.DeepLinkBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** What the in-progress "resume" banner above the bottom bar should show. */
sealed interface ActiveBarUi {
    /** A live strength workout. Set progress is from Room; the elapsed clock ticks off [startedAtMs]. */
    data class Workout(
        val sessionId: String,
        val doneSets: Int,
        val totalSets: Int,
        val startedAtMs: Long?,
    ) : ActiveBarUi

    /** A live or paused cardio run. [detail] is pre-formatted (phase + countdown, or "Paused · m:ss"). */
    data class Cardio(
        val session: CardioSessionEntity,
        val title: String,
        val detail: String,
    ) : ActiveBarUi
}

/**
 * Shell-level state: the single in-progress session the resume banner points at. A workout and a
 * cardio run can in principle both be in progress; when they are, **cardio wins** — it has a live
 * ticking timer and a running foreground service, so it's the more time-critical thing to resume.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppShellViewModel @Inject constructor(
    activeWorkoutStore: ActiveWorkoutStore,
    activeCardioStore: ActiveCardioStore,
    private val cardioController: CardioRunController,
    private val cardioRepository: CardioRepository,
    setLogDao: SetLogDao,
    deepLinkBus: DeepLinkBus,
) : ViewModel() {

    val deepLinks: SharedFlow<com.spotter.util.DeepLinkTarget> = deepLinkBus.targets

    private val workoutBar: Flow<ActiveBarUi.Workout?> =
        activeWorkoutStore.activeSession.flatMapLatest { session ->
            if (session == null) {
                flowOf(null)
            } else {
                setLogDao.observeBySession(session.id).map { logs ->
                    ActiveBarUi.Workout(
                        sessionId = session.id,
                        doneSets = logs.count { it.completed },
                        totalSets = logs.size,
                        startedAtMs = session.startedAtMs,
                    )
                }
            }
        }

    private val cardioBar: Flow<ActiveBarUi.Cardio?> =
        combine(activeCardioStore.activeCardio, cardioController.state) { session, run ->
            if (session == null) return@combine null
            val title = CardioPrograms.byId(session.programId)?.name ?: "Cardio"
            val detail = if (run != null) {
                when {
                    run.isComplete -> "Done · ${CardioFormat.clock(run.totalElapsedSec)}"
                    run.isPaused -> "Paused · ${CardioFormat.clock(run.totalElapsedSec)}"
                    run.isOpenEnded -> "Running · ${CardioFormat.clock(run.totalElapsedSec)}"
                    else -> "${run.phase.label} · ${CardioFormat.clock(run.intervalRemainingSec)} left"
                }
            } else {
                // Left the run screen — session is paused in Room; show its frozen elapsed.
                "Paused · ${CardioFormat.clock(session.totalElapsedSec)}"
            }
            ActiveBarUi.Cardio(session = session, title = title, detail = detail)
        }

    val activeBar: StateFlow<ActiveBarUi?> =
        combine(workoutBar, cardioBar) { workout, cardio -> cardio ?: workout }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Re-arm the cardio controller for a paused/left session so the run screen comes back live. */
    fun resumeCardio(session: CardioSessionEntity) {
        if (session.programId == CardioPrograms.FREE_RUN_ID) {
            // Free-run intervals aren't persisted; resume as an open-ended run from its elapsed.
            cardioController.startFree(openEnded = true, intervals = emptyList(), resume = session)
            return
        }
        val week = session.weekNumber
        val day = session.dayNumber
        val intervals = if (week != null && day != null) {
            CardioPrograms.dayIntervals(session.programId, week, day)
        } else {
            null
        }
        if (week != null && day != null && intervals != null) {
            cardioController.startGuided(
                programId = session.programId,
                week = week,
                day = day,
                intervals = intervals,
                label = CardioPrograms.byId(session.programId)?.name ?: "Couch to 5K",
                weekDayLabel = "WEEK $week DAY $day",
                resume = session,
            )
        }
    }

    /** Look up a cardio session by local id (for notification deep-links). */
    suspend fun cardioSession(id: String): CardioSessionEntity? = cardioRepository.getLocal(id)
}
