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
)

@Serializable
data class SessionOut(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("plan_id") val planId: String? = null,
    val date: String,
    val status: String,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    val note: String? = null,
    @SerialName("set_logs") val setLogs: List<SetLogOut> = emptyList(),
)
