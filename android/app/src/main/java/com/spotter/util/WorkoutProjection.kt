package com.spotter.util

import com.spotter.data.local.entity.RoutineExerciseEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Minimal info about the most recent local session, used to anchor projections. */
data class SessionAnchor(
    val date: LocalDate,
    val routineId: String?,
    val status: String, // "completed" | "in_progress" | other
)

/** A program day reduced to what projection needs. Supplied sorted by `order`. */
data class ProjectionDay(
    val routineId: String?,
    val label: String,
    val routineName: String?,
)

/** One projected upcoming workout slot (lifts are attached separately by the caller). */
data class ProjectedSlot(
    val date: LocalDate,
    val routineId: String?,
    val label: String,
    val routineName: String?,
    /** 0-based position of this day within the program — drives per-day calendar colors. */
    val dayIndex: Int,
)

/**
 * A scheduled cardio run riding the same upcoming-workout rail as strength days. When present on an
 * [UpcomingWorkout], the slot is a cardio run (Couch to 5K week/day) rather than a strength routine,
 * so it has no [UpcomingWorkout.routineId] and is opened via the Cardio overview instead of started
 * in place.
 */
data class CardioUpcoming(
    val programId: String,
    val programName: String,
    val week: Int,
    val day: Int,
    val totalDurationSec: Int,
)

/**
 * A fully-resolved upcoming workout: a projected slot plus a short preview of its lifts.
 * Shared by the Home upcoming block and the Calendar projected-day detail card.
 *
 * A slot is a cardio run when [cardio] is non-null (in which case [routineId] is null and [lifts]
 * is empty); otherwise it is a strength routine day (or a strength rest day when [routineId] is null
 * and [cardio] is null).
 */
data class UpcomingWorkout(
    val date: LocalDate,
    val dayLabel: String,
    val routineId: String?,
    val routineName: String?,
    val lifts: List<RoutineExerciseEntity>,
    /** 0-based position of this day within the program — drives per-day calendar colors. */
    val dayIndex: Int = 0,
    val cardio: CardioUpcoming? = null,
)

/**
 * Projects the next workouts of the active program onto real dates from an "every N days"
 * cadence. The schedule is re-derived from the latest real session on every call, so finishing
 * a workout early (or having one still active) automatically shifts everything — there is no
 * stored schedule to keep in sync.
 */
object WorkoutProjection {

    /**
     * Returns the effective cadence to use for projection.
     *
     * When the program has explicit rest days (routineId == null), the program itself encodes the
     * weekly structure, so each day maps to one calendar day (step = 1). The user's cadence
     * preference applies only to programs where every day is a workout day.
     */
    fun effectiveCadence(cadenceDays: Int, days: List<ProjectionDay>): Int =
        if (days.any { it.routineId == null }) 1 else cadenceDays.coerceAtLeast(1)

    /**
     * @param today the current date.
     * @param cadenceDays N — days between workouts (coerced to >= 1). Ignored when the program
     *   has rest days; see [effectiveCadence].
     * @param anchor the most recent session (completed OR in_progress), or null if none.
     * @param days the active program's days, ordered by `order`. Empty ⇒ no projection.
     * @param count how many upcoming slots to produce.
     *
     * Date rules:
     *  - With an anchor: first date = anchor.date + step, advanced forward in +step steps until it
     *    is >= today (handles a stale past anchor). An in_progress session dated today therefore
     *    yields a first date of today + step.
     *  - Without an anchor: first date = today.
     *  - Each later slot is +step after the previous.
     *
     * Day-selection rules (mirrors the server `get_next_day` cyclic logic, extended to N slots):
     *  - startIndex = index of the day whose routineId == anchor.routineId, else -1 (no match / no anchor).
     *  - slot k (0-based) uses day at ((startIndex + 1 + k) mod size).
     */
    fun project(
        today: LocalDate,
        cadenceDays: Int,
        anchor: SessionAnchor?,
        days: List<ProjectionDay>,
        count: Int = 2,
    ): List<ProjectedSlot> {
        if (days.isEmpty() || count <= 0) return emptyList()
        val n = days.size
        val step = effectiveCadence(cadenceDays, days).toLong()

        val firstDate = if (anchor != null) {
            var d = anchor.date.plusDays(step)
            while (d.isBefore(today)) d = d.plusDays(step)
            d
        } else {
            today
        }

        val startIndex = anchor?.routineId?.let { rid -> days.indexOfFirst { it.routineId == rid } } ?: -1
        val hasRestDays = days.any { it.routineId == null }

        return (0 until count).map { k ->
            val date = firstDate.plusDays(step * k)
            // A program with explicit rest days is calendar-structured (step == 1), so each slot's
            // position is derived from its DATE — the same anchor→offset arithmetic restDayDatesInRange
            // uses. This auto-consumes a rest day once its date has passed, instead of the position
            // freezing at anchor+1 and leaving "next up" stuck on a rest day forever (rest days can
            // never be "completed", so a completion-only anchor never advanced past them). Cadence
            // programs (no rest days) keep the per-workout stepping: the next workout is always next,
            // whenever the user gets to it.
            val dayIndex = if (anchor != null && hasRestDays) {
                val offset = ChronoUnit.DAYS.between(anchor.date, date)
                (((startIndex.toLong() + offset) % n + n) % n).toInt()
            } else {
                // ((startIndex + 1 + k) % n + n) % n stays valid when startIndex == -1.
                ((startIndex + 1 + k) % n + n) % n
            }
            val day = days[dayIndex]
            ProjectedSlot(
                date = date,
                routineId = day.routineId,
                label = day.label,
                routineName = day.routineName,
                dayIndex = dayIndex,
            )
        }
    }

    /**
     * Returns all dates in [[startDate]..[endDate]] that map to program rest days
     * (routineId == null). Uses the same anchor → index arithmetic as [project] so the
     * mapping is consistent with the forward calendar projection.
     *
     * Returns an empty set when the program has no rest days (nothing to skip in the
     * streak walk). Only meaningful when the effective cadence is 1 (i.e. the program
     * contains rest days — which is the only situation this is called for).
     */
    fun restDayDatesInRange(
        anchor: SessionAnchor?,
        days: List<ProjectionDay>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): Set<LocalDate> {
        if (days.none { it.routineId == null }) return emptySet()
        val n = days.size
        val anchorDate = anchor?.date ?: startDate
        val startIndex = anchor?.routineId
            ?.let { rid -> days.indexOfFirst { it.routineId == rid } }
            ?: -1
        val result = mutableSetOf<LocalDate>()
        var d = startDate
        while (!d.isAfter(endDate)) {
            val offset = ChronoUnit.DAYS.between(anchorDate, d)
            // Safe modulo for negative offsets (past dates).
            val raw = (startIndex.toLong() + offset) % n.toLong()
            val idx = ((raw + n.toLong()) % n.toLong()).toInt()
            if (days[idx].routineId == null) result.add(d)
            d = d.plusDays(1)
        }
        return result
    }
}
