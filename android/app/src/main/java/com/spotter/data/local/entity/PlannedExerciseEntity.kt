package com.spotter.data.local.entity

import androidx.room.Entity

@Entity(tableName = "planned_exercises", primaryKeys = ["planId", "exerciseId"])
data class PlannedExerciseEntity(
    val planId: String,
    val exerciseId: String,
    val exerciseName: String?,
    val targetSets: Int,
    val targetReps: Int,
    val targetWeight: Double?,
    val isBodyweight: Boolean,
    val order: Int,
)
