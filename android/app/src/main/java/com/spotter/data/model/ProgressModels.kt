package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackedExercise(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("exercise_name") val exerciseName: String,
)

@Serializable
data class ExerciseProgressPoint(
    val date: String,
    @SerialName("max_weight") val maxWeight: Double? = null,
    @SerialName("max_reps") val maxReps: Int,
)
