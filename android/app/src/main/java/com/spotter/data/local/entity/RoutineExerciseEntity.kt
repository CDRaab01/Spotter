package com.spotter.data.local.entity

import androidx.room.Entity

@Entity(tableName = "routine_exercises", primaryKeys = ["routineId", "exerciseId"])
data class RoutineExerciseEntity(
    val routineId: String,
    val exerciseId: String,
    val exerciseName: String?,
    val targetSets: Int,
    val targetReps: Int,
    val targetWeight: Double?,
    val isBodyweight: Boolean,
    val order: Int,
    val supersetGroup: Int? = null,
    /** Per-exercise rest override in seconds; null = rep-range heuristic. */
    val restSeconds: Int? = null,
)
