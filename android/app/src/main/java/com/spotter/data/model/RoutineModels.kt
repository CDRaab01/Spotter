package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RoutineUpdate(val name: String)

@Serializable
data class RoutineExerciseIn(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("target_sets") val targetSets: Int,
    @SerialName("target_reps") val targetReps: Int,
    @SerialName("target_weight") val targetWeight: Double? = null,
    @SerialName("is_bodyweight") val isBodyweight: Boolean = false,
    val order: Int = 0,
    @SerialName("superset_group") val supersetGroup: Int? = null,
)

@Serializable
data class RoutineCreate(
    val name: String,
    val source: String = "manual",
    val exercises: List<RoutineExerciseIn> = emptyList(),
)

@Serializable
data class RoutineExerciseOut(
    val id: String,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("target_sets") val targetSets: Int,
    @SerialName("target_reps") val targetReps: Int,
    @SerialName("target_weight") val targetWeight: Double? = null,
    @SerialName("is_bodyweight") val isBodyweight: Boolean = false,
    val order: Int = 0,
    @SerialName("exercise_name") val exerciseName: String? = null,
    @SerialName("superset_group") val supersetGroup: Int? = null,
)

@Serializable
data class RoutineExercisesUpdate(val exercises: List<RoutineExerciseIn>)

@Serializable
data class RoutineOut(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val source: String,
    @SerialName("created_at") val createdAt: String,
    val exercises: List<RoutineExerciseOut> = emptyList(),
)
