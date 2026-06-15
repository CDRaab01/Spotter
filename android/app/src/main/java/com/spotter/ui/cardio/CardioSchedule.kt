package com.spotter.ui.cardio

import com.spotter.data.local.entity.CardioSessionEntity
import com.spotter.data.model.CardioProgram
import com.spotter.data.model.CardioStatus
import com.spotter.util.CardioUpcoming
import com.spotter.util.UpcomingWorkout
import java.time.LocalDate

/**
 * Scheduling math for guided cardio programs (Couch to 5K), shared by the Cardio overview screen
 * and the Home / Calendar "upcoming" projections so all three agree on which run is next and when.
 *
 * Cardio definitions are static client-side and only *session* records persist, so the schedule is
 * derived purely from completed sessions: the first day with no completed session is "current", and
 * later days are projected forward on a 3-per-week cadence (2 / 2 / 3 days apart). A target date is
 * never in the past — an overdue run lands on today.
 */
object CardioSchedule {

    /** Days between consecutive runs, cycling for a 3-per-week rhythm. */
    val CADENCE = listOf(2L, 2L, 3L)

    /** A program day flattened to what scheduling needs. */
    data class DayRef(val week: Int, val day: Int, val totalDurationSec: Int)

    /** All days of a guided program in order; empty for a free-run / unknown program. */
    fun orderedDays(program: CardioProgram): List<DayRef> =
        program.weeks.orEmpty().flatMap { w ->
            w.days.map { d -> DayRef(w.weekNumber, d.dayNumber, d.totalDurationSec) }
        }

    /** Latest completion date per (week, day) for completed sessions. */
    fun completedDates(sessions: List<CardioSessionEntity>): Map<Pair<Int, Int>, LocalDate> {
        val out = HashMap<Pair<Int, Int>, LocalDate>()
        sessions
            .filter { it.status == CardioStatus.COMPLETED && it.weekNumber != null && it.dayNumber != null }
            .forEach { s ->
                val key = s.weekNumber!! to s.dayNumber!!
                val date = CardioFormat.parseDate(s.completedAt) ?: CardioFormat.parseDate(s.startedAt)
                if (date != null && out[key]?.isAfter(date) != true) out[key] = date
            }
        return out
    }

    /**
     * Target date per ordered index for every not-yet-completed day, projected from the last
     * completed run (or from today when nothing has been completed). Indices already completed are
     * omitted; overdue targets are clamped to [today].
     */
    fun targetDates(
        ordered: List<DayRef>,
        completed: Map<Pair<Int, Int>, LocalDate>,
        today: LocalDate,
    ): Map<Int, LocalDate> {
        val out = HashMap<Int, LocalDate>()
        val completedKeys = ordered.indices.filter { (ordered[it].week to ordered[it].day) in completed }
        val lastCompletedPos = completedKeys.maxOrNull() ?: -1
        if (lastCompletedPos >= 0) {
            var d = completed[ordered[lastCompletedPos].week to ordered[lastCompletedPos].day]!!
            for (pos in lastCompletedPos + 1 until ordered.size) {
                d = d.plusDays(CADENCE[pos % CADENCE.size])
                out[pos] = if (d.isBefore(today)) today else d
            }
        } else {
            var d = today
            for (pos in ordered.indices) {
                if (pos == 0) {
                    out[pos] = d
                } else {
                    d = d.plusDays(CADENCE[(pos - 1) % CADENCE.size])
                    out[pos] = d
                }
            }
        }
        return out
    }

    /**
     * The next [count] incomplete runs of [program], each as an [UpcomingWorkout] carrying a
     * [CardioUpcoming] payload, ordered by target date. Returns empty for a non-guided program.
     */
    fun upcoming(
        program: CardioProgram,
        sessions: List<CardioSessionEntity>,
        today: LocalDate,
        count: Int,
    ): List<UpcomingWorkout> {
        if (count <= 0) return emptyList()
        val ordered = orderedDays(program)
        if (ordered.isEmpty()) return emptyList()
        val completed = completedDates(sessions)
        val targets = targetDates(ordered, completed, today)
        return ordered.indices
            .mapNotNull { idx -> targets[idx]?.let { idx to it } }
            .sortedBy { it.second }
            .take(count)
            .map { (idx, date) ->
                val ref = ordered[idx]
                UpcomingWorkout(
                    date = date,
                    dayLabel = "${program.name} · Week ${ref.week} Day ${ref.day}",
                    routineId = null,
                    routineName = program.name,
                    lifts = emptyList(),
                    cardio = CardioUpcoming(
                        programId = program.id,
                        programName = program.name,
                        week = ref.week,
                        day = ref.day,
                        totalDurationSec = ref.totalDurationSec,
                    ),
                )
            }
    }
}
