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

@Serializable
data class ChatResponse(
    val reply: String,
    @SerialName("suggested_routine") val suggestedRoutine: SuggestedRoutine? = null,
    @SerialName("suggested_program") val suggestedProgram: SuggestedProgram? = null,
)
