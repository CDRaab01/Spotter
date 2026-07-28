package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * The AI suggestion cards this assistant turn produced, as a serialized
     * [com.spotter.data.model.PendingSuggestions] envelope — null when the turn proposed
     * nothing. A suggestion is an attribute of the turn that produced it, so it lives on the
     * message row and survives process death with the transcript. Local-only state: chat
     * history is never synced to the server.
     */
    val suggestionsJson: String? = null,
    /**
     * The local workout session id the turn was about, or null for a normal (non-session-aware)
     * chat. Only a chat opened on that same session may restore the envelope's adjustment card.
     */
    val suggestionSessionId: String? = null,
)
