package com.spotter.ui.cardio

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.model.CardioProgram
import com.spotter.data.model.CardioStatus
import com.spotter.data.model.Interval
import com.spotter.data.repository.CardioRepository
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
) : ViewModel() {

    private val programId: String = savedStateHandle["programId"] ?: CardioPrograms.C25K_ID
    private val program: CardioProgram? = CardioPrograms.byId(programId)

    // Days the user is allowed between sessions, cycling for a 3-per-week cadence.
    private val cadence = listOf(2L, 2L, 3L)

    val uiState: StateFlow<CardioOverviewUi> =
        repository.sessionsFor(programId)
            .map { sessions -> build(sessions) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                CardioOverviewUi(program?.name ?: "Cardio", emptyList()),
            )

    init {
        viewModelScope.launch {
            try { repository.sync(programId) } catch (_: Exception) {}
        }
    }

    private fun build(sessions: List<CardioSessionEntity>): CardioOverviewUi {
        val weeks = program?.weeks ?: return CardioOverviewUi(program?.name ?: "Cardio", emptyList())

        val ordered = weeks.flatMap { w -> w.days.map { d -> w.weekNumber to d.dayNumber } }

        // Latest completed session per (week, day).
        val completedAt = HashMap<Pair<Int, Int>, LocalDate>()
        sessions.filter { it.status == CardioStatus.COMPLETED && it.weekNumber != null && it.dayNumber != null }
            .forEach { s ->
                val key = s.weekNumber!! to s.dayNumber!!
                val date = CardioFormat.parseDate(s.completedAt) ?: CardioFormat.parseDate(s.startedAt)
                if (date != null && completedAt[key]?.isAfter(date) != true) completedAt[key] = date
            }

        val currentIndex = ordered.indexOfFirst { it !in completedAt.keys }
            .let { if (it == -1) ordered.size else it }

        val today = LocalDate.now()
        val inProgressToday = sessions
            .filter { it.status == CardioStatus.IN_PROGRESS }
            .filter { CardioFormat.parseDate(it.startedAt) == today }
            .maxByOrNull { it.startedAt }

        val targets = targetDates(ordered, completedAt, today)

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

    private fun targetDates(
        ordered: List<Pair<Int, Int>>,
        completed: Map<Pair<Int, Int>, LocalDate>,
        today: LocalDate,
    ): Map<Int, LocalDate> {
        val out = HashMap<Int, LocalDate>()
        val lastCompletedPos = ordered.indexOfLast { it in completed.keys }
        if (lastCompletedPos >= 0) {
            var d = completed[ordered[lastCompletedPos]]!!
            for (pos in lastCompletedPos + 1 until ordered.size) {
                d = d.plusDays(cadence[pos % cadence.size])
                out[pos] = if (d.isBefore(today)) today else d
            }
        } else {
            var d = today
            for (pos in ordered.indices) {
                if (pos == 0) {
                    out[pos] = d
                } else {
                    d = d.plusDays(cadence[(pos - 1) % cadence.size])
                    out[pos] = d
                }
            }
        }
        return out
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
