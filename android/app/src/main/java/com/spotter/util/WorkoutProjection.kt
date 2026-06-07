package com.spotter.util

import com.spotter.data.local.entity.RoutineExerciseEntity
import java.time.LocalDate

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
)

/**
 * A fully-resolved upcoming workout: a projected slot plus a short preview of its lifts.
 * Shared by the Home upcoming block and the Calendar projected-day detail card.
 */
data class UpcomingWorkout(
    val date: LocalDate,
    val dayLabel: String,
    val routineId: String?,
    val routineName: String?,
    val lifts: List<RoutineExerciseEntity>,
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

        return (0 until count).map { k ->
            // ((startIndex + 1 + k) % n + n) % n stays valid when startIndex == -1.
            val day = days[((startIndex + 1 + k) % n + n) % n]
            ProjectedSlot(
                date = firstDate.plusDays(step * k),
                routineId = day.routineId,
                label = day.label,
                routineName = day.routineName,
            )
        }
    }
}
