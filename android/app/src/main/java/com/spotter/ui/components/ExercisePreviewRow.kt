package com.spotter.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.spotter.data.local.entity.RoutineExerciseEntity
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.formatWeight

/**
 * A compact one-line preview of a planned exercise, e.g. "Bench Press · 4×8×135lb" or
 * "Pull-up · 3×8 BW". Shared by the Home upcoming block and the Calendar projected-day card.
 * Mirrors the detail formatting of `ExerciseViewRow` on the plan-detail screen.
 */
@Composable
fun ExercisePreviewRow(
    exercise: RoutineExerciseEntity,
    modifier: Modifier = Modifier,
) {
    val weightUnit = LocalWeightUnit.current
    val detail = if (exercise.isBodyweight) {
        "${exercise.targetSets}×${exercise.targetReps} BW"
    } else {
        val weight = exercise.targetWeight?.let { "×${weightUnit.formatWeight(it)}" } ?: ""
        "${exercise.targetSets}×${exercise.targetReps}$weight"
    }
    Text(
        text = "${exercise.exerciseName ?: "Exercise"} · $detail",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}
