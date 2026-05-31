package com.spotter.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(val messages: List<ChatMessage>)

@Serializable
data class ChatResponse(val reply: String)
