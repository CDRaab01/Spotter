package com.spotter.ui.cardio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.model.CardioProgram
import com.spotter.data.model.CardioStatus
import com.spotter.data.model.Interval
import com.spotter.data.repository.CardioRepository
import com.spotter.util.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

enum class CardioDayStatus { DONE, CURRENT, UPCOMING }

data class CardioDayUi(
    val week: Int,
    val day: Int,
    val totalDurationSec: Int,
    val intervals: List<Interval>,
    val status: CardioDayStatus,
    val completedDate: LocalDate? = null,
    val targetDate: LocalDate? = null,
    val attemptedToday: Boolean = false,
    val resumeSession: CardioSessionEntity? = null,
)

data class CardioWeekUi(
    val weekNumber: Int,
    val intro: String,
    val days: List<CardioDayUi>,
)

data class CardioOverviewUi(
    val programName: String,
    val weeks: List<CardioWeekUi>,
)

@HiltViewModel
class CardioOverviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: CardioRepository,
    private val controller: CardioRunController,
    private val appPreferences: AppPreferences,
) : ViewModel() {

    private val programId: String = savedStateHandle["programId"] ?: CardioPrograms.C25K_ID
    private val program: CardioProgram? = CardioPrograms.byId(programId)

    val uiState: StateFlow<CardioOverviewUi> =
        repository.sessionsFor(programId)
            .map { sessions -> build(sessions) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                CardioOverviewUi(program?.name ?: "Cardio", emptyList()),
            )

    /** True when this program is the user's active cardio program (its runs show on Home/Calendar). */
    val isOnSchedule: StateFlow<Boolean> =
        appPreferences.activeCardioProgramId
            .map { it == programId }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        viewModelScope.launch {
            try { repository.sync(programId) } catch (_: Exception) {}
        }
    }

    /** Add this program to the schedule (or remove it) so upcoming runs surface on Home/Calendar. */
    fun setOnSchedule(onSchedule: Boolean) {
        viewModelScope.launch {
            appPreferences.setActiveCardioProgram(if (onSchedule) programId else null)
        }
    }

    private fun build(sessions: List<CardioSessionEntity>): CardioOverviewUi {
        val weeks = program?.weeks ?: return CardioOverviewUi(program?.name ?: "Cardio", emptyList())

        val ordered = CardioSchedule.orderedDays(program)
        val completedAt = CardioSchedule.completedDates(sessions)
        val today = LocalDate.now()
        val targets = CardioSchedule.targetDates(ordered, completedAt, today)

        val currentIndex = ordered.indexOfFirst { (it.week to it.day) !in completedAt.keys }
            .let { if (it == -1) ordered.size else it }

        val inProgressToday = sessions
            .filter { it.status == CardioStatus.IN_PROGRESS }
            .filter { CardioFormat.parseDate(it.startedAt) == today }
            .maxByOrNull { it.startedAt }

        var globalIndex = 0
        val weekUis = weeks.map { w ->
            val dayUis = w.days.map { d ->
                val key = w.weekNumber to d.dayNumber
                val idx = globalIndex++
                val status = when {
                    key in completedAt.keys -> CardioDayStatus.DONE
                    idx == currentIndex -> CardioDayStatus.CURRENT
                    else -> CardioDayStatus.UPCOMING
                }
                val isCurrent = status == CardioDayStatus.CURRENT
                val resume = if (isCurrent && inProgressToday?.weekNumber == w.weekNumber &&
                    inProgressToday.dayNumber == d.dayNumber
                ) inProgressToday else null
                CardioDayUi(
                    week = w.weekNumber,
                    day = d.dayNumber,
                    totalDurationSec = d.totalDurationSec,
                    intervals = d.intervals,
                    status = status,
                    completedDate = completedAt[key],
                    targetDate = if (status == CardioDayStatus.DONE) null else targets[idx],
                    attemptedToday = resume != null,
                    resumeSession = resume,
                )
            }
            CardioWeekUi(w.weekNumber, w.intro, dayUis)
        }
        return CardioOverviewUi(program.name, weekUis)
    }

    /** Resume an in-progress session for this day. */
    fun resume(day: CardioDayUi) = launchDay(day, day.resumeSession)

    /** Start (or restart) this day from the beginning. */
    fun start(day: CardioDayUi) = launchDay(day, resume = null)

    private fun launchDay(day: CardioDayUi, resume: CardioSessionEntity?) {
        controller.startGuided(
            programId = programId,
            week = day.week,
            day = day.day,
            intervals = day.intervals,
            label = program?.name ?: "Couch to 5K",
            weekDayLabel = "WEEK ${day.week} DAY ${day.day}",
            resume = resume,
        )
    }
}
