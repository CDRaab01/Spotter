package com.spotter.data.repository

import com.spotter.data.local.entity.SetLogEntity
import com.spotter.data.model.MuscleGroupSummary
import kotlin.math.round

/**
 * Local (offline) computation of a session's per-muscle-group breakdown, mirroring the server's
 * `session_service.get_session` aggregation so an offline-finished workout's summary matches what
 * the server would have returned:
 *
 * - only **completed** sets count;
 * - a set with no known muscle group (exercise missing from the mirror, or an uncategorised
 *   catalog row) is skipped entirely — same as the server's falsy check;
 * - **volume is metric (kg)**: `reps × weight_lb × 0.453592`, and — matching the server's Python
 *   truthiness (`if sl.weight:`) — a null *or zero* weight contributes sets but no volume;
 * - volume rounds to one decimal; groups sort alphabetically.
 *
 * Pure function so the semantics are table-testable in isolation (the `progression.py` /
 * merge-module precedent).
 */
object OfflineMuscleGroups {

    private const val LB_TO_KG = 0.453592

    /**
     * @param muscleGroupByExerciseId exerciseId → muscle group from the exercise mirror; absent
     *   or null/blank entries degrade that exercise out of the summary (never a crash).
     */
    fun summarize(
        setLogs: List<SetLogEntity>,
        muscleGroupByExerciseId: Map<String, String?>,
    ): List<MuscleGroupSummary> {
        val sets = mutableMapOf<String, Int>()
        val volume = mutableMapOf<String, Double>()
        for (sl in setLogs) {
            if (!sl.completed) continue
            val mg = muscleGroupByExerciseId[sl.exerciseId]
            if (mg.isNullOrEmpty()) continue
            sets[mg] = (sets[mg] ?: 0) + 1
            val weight = sl.weight
            if (weight != null && weight != 0.0) {
                volume[mg] = (volume[mg] ?: 0.0) + sl.reps * (weight * LB_TO_KG)
            }
        }
        return sets.keys.sorted().map { mg ->
            MuscleGroupSummary(
                muscleGroup = mg,
                sets = sets.getValue(mg),
                // Python's round() banker's-rounds exact .5 ties; this rounds them up. For a
                // one-decimal display volume the divergence is cosmetic and not worth mirroring.
                volume = (round((volume[mg] ?: 0.0) * 10.0) / 10.0).toFloat(),
            )
        }
    }
}
