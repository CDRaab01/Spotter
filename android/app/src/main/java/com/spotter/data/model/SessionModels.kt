package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionCreate(
    @SerialName("plan_id") val planId: String? = null,
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
data class SessionOut(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("plan_name") val planName: String? = null,
    val date: String,
    val status: String,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val note: String? = null,
    @SerialName("exercise_notes") val exerciseNotes: Map<String, String>? = null,
    @SerialName("set_logs") val setLogs: List<SetLogOut> = emptyList(),
)

@Serializable
data class ExercisePrior(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("exercise_name") val exerciseName: String? = null,
    val reps: Int,
    val weight: Double? = null,
    val date: String,
)
