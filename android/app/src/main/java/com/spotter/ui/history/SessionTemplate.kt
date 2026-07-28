package com.spotter.ui.history

import com.spotter.data.model.RoutineExerciseIn
import com.spotter.data.model.SessionOut
import com.spotter.data.model.SetLogOut

/**
 * Turning a performed workout back into a reusable routine ("Save as routine").
 *
 * Pure on purpose — the rules are opinionated enough to deserve their own tests:
 * - only **completed** sets count (a skipped set was not part of the workout that happened);
 * - exercise order follows first appearance in the log, i.e. the order they were trained;
 * - `target_sets` is how many sets were actually completed;
 * - `target_reps` / `target_weight` take the most common completed value, ties going to the
 *   latest set (the weight you finished on beats the one you warmed up with);
 * - an exercise whose completed sets all logged no weight becomes bodyweight.
 */
internal fun routineExercisesFromSession(session: SessionOut): List<RoutineExerciseIn> {
    val completed = session.setLogs.filter { it.completed }
    if (completed.isEmpty()) return emptyList()
    // groupBy keeps first-encounter order, which is the order the exercises were trained in.
    return completed
        .groupBy { it.exerciseId }
        .entries
        .mapIndexed { order, (exerciseId, rawSets) ->
            val sets = rawSets.sortedBy { it.setNumber }
            val isBodyweight = sets.all { it.weight == null }
            RoutineExerciseIn(
                exerciseId = exerciseId,
                targetSets = sets.size,
                targetReps = modeOrLatest(sets) { it.reps } ?: sets.last().reps,
                targetWeight = if (isBodyweight) null else modeOrLatest(sets) { it.weight },
                isBodyweight = isBodyweight,
                order = order,
                supersetGroup = sets.first().supersetGroup,
            )
        }
}

/** The most frequent non-null value of [select]; ties (and the empty case) resolve to the last. */
private fun <T : Any> modeOrLatest(sets: List<SetLogOut>, select: (SetLogOut) -> T?): T? {
    val values = sets.mapNotNull(select)
    if (values.isEmpty()) return null
    val counts = values.groupingBy { it }.eachCount()
    val best = counts.values.max()
    // Scan from the end so the latest of the tied values wins.
    return values.last { counts[it] == best }
}

/** "Push Day (copy)" / "2026-07-28 (copy)" — a name that reads as derived, not original. */
internal fun copiedRoutineName(session: SessionOut): String {
    val base = session.routineName?.takeIf { it.isNotBlank() } ?: session.date
    return "$base (copy)"
}
