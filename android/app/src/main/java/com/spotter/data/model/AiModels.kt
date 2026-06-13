package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    @SerialName("user_context") val userContext: String? = null,
    @SerialName("current_session_id") val currentSessionId: String? = null,
)

@Serializable
data class SuggestedRoutine(
    val name: String,
    val exercises: List<RoutineExerciseIn>,
)

@Serializable
data class SuggestedProgramDay(
    val label: String,
    val exercises: List<RoutineExerciseIn> = emptyList(),
    val order: Int = 0,
)

@Serializable
data class SuggestedProgram(
    val name: String,
    val days: List<SuggestedProgramDay>,
)

@Serializable
data class AcceptProgramRequest(
    val name: String,
    val days: List<SuggestedProgramDay>,
)

/**
 * One resolved action of an AI-proposed live workout adjustment. Echoed back verbatim
 * to POST /ai/sessions/{id}/adjust when the user taps Apply (the server re-validates).
 */
@Serializable
data class SuggestedAdjustmentAction(
    val type: String, // swap | adjust_weight | remove | add
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("exercise_name") val exerciseName: String,
    @SerialName("new_exercise_id") val newExerciseId: String? = null,
    @SerialName("new_exercise_name") val newExerciseName: String? = null,
    val sets: Int? = null,
    val reps: Int? = null,
    val weight: Double? = null,
    val summary: String = "",
)

@Serializable
data class SuggestedAdjustment(
    val actions: List<SuggestedAdjustmentAction>,
)

@Serializable
data class ApplyAdjustmentRequest(
    val actions: List<SuggestedAdjustmentAction>,
    @SerialName("apply_to_routine") val applyToRoutine: Boolean = true,
)

@Serializable
data class ChatResponse(
    val reply: String,
    @SerialName("suggested_routine") val suggestedRoutine: SuggestedRoutine? = null,
    @SerialName("suggested_program") val suggestedProgram: SuggestedProgram? = null,
    @SerialName("suggested_adjustment") val suggestedAdjustment: SuggestedAdjustment? = null,
)
