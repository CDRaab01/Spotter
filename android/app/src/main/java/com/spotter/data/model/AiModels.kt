package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(val messages: List<ChatMessage>)

@Serializable
data class SuggestedPlan(
    val name: String,
    val exercises: List<PlannedExerciseIn>,
)

@Serializable
data class ChatResponse(
    val reply: String,
    @SerialName("suggested_plan") val suggestedPlan: SuggestedPlan? = null,
)
