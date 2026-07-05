package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionCreate(
    @SerialName("routine_id") val routineId: String? = null,
    val date: String,
    val note: String? = null,
)

@Serializable
data class SetLogCreate(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("set_number") val setNumber: Int,
    val reps: Int,
    val weight: Double? = null,
    val completed: Boolean = false,
)

@Serializable
data class SetLogOut(
    val id: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("set_number") val setNumber: Int,
    val reps: Int,
    val weight: Double? = null,
    val completed: Boolean = false,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("exercise_name") val exerciseName: String? = null,
    @SerialName("target_sets") val targetSets: Int? = null,
    @SerialName("target_reps") val targetReps: Int? = null,
    @SerialName("target_weight") val targetWeight: Double? = null,
    @SerialName("superset_group") val supersetGroup: Int? = null,
)

@Serializable
data class SessionUpdate(
    val status: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val note: String? = null,
    @SerialName("exercise_notes") val exerciseNotes: Map<String, String>? = null,
)

@Serializable
data class SetLogUpdate(
    val reps: Int? = null,
    val weight: Double? = null,
    val completed: Boolean? = null,
)

@Serializable
data class MuscleGroupSummary(
    @SerialName("muscle_group") val muscleGroup: String,
    val sets: Int,
    val volume: Float,
)

@Serializable
data class SessionOut(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("routine_id") val routineId: String? = null,
    @SerialName("routine_name") val routineName: String? = null,
    val date: String,
    val status: String,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val note: String? = null,
    @SerialName("exercise_notes") val exerciseNotes: Map<String, String>? = null,
    @SerialName("set_logs") val setLogs: List<SetLogOut> = emptyList(),
    @SerialName("muscle_groups") val muscleGroups: List<MuscleGroupSummary> = emptyList(),
)

@Serializable
data class ExerciseSummary(
    @SerialName("exercise_name") val exerciseName: String,
    @SerialName("completed_sets") val completedSets: Int,
    @SerialName("total_sets") val totalSets: Int,
)

@Serializable
data class SessionSummary(
    val id: String,
    val date: String,
    @SerialName("routine_name") val routineName: String? = null,
    val status: String,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("total_sets") val totalSets: Int,
    @SerialName("completed_sets") val completedSets: Int,
    val exercises: List<ExerciseSummary> = emptyList(),
)

@Serializable
data class ExercisePrior(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("exercise_name") val exerciseName: String? = null,
    val reps: Int,
    val weight: Double? = null,
    val date: String,
    @SerialName("last_sets") val lastSets: List<SetLogOut> = emptyList(),
    @SerialName("suggested_weight") val suggestedWeight: Double? = null,
    @SerialName("suggested_reason") val suggestedReason: String? = null,
    // Progressive-overload engine (ROADMAP2 T3 #1)
    @SerialName("suggested_reps") val suggestedReps: Int? = null,
    val action: String? = null, // add_weight | add_reps | hold | deload | bodyweight
    val e1rm: Double? = null,
    @SerialName("is_pr") val isPr: Boolean = false,
)
