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
    // Best estimated 1RM among that day's sets (per-set Epley); null for bodyweight exercises.
    @SerialName("est_1rm") val est1rm: Double? = null,
)

@Serializable
data class PersonalRecord(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("exercise_name") val exerciseName: String,
    @SerialName("max_weight") val maxWeight: Double,
    @SerialName("max_weight_reps") val maxWeightReps: Int,
    @SerialName("best_est_1rm") val bestEst1rm: Double,
    @SerialName("best_volume") val bestVolume: Double,
    @SerialName("achieved_on") val achievedOn: String,
)
