package com.spotter.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.spotter.data.local.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessageEntity>>

    /** Returns the new row id so the caller can later clear that row's suggestion envelope. */
    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    /**
     * The newest assistant turn — the only one allowed to restore suggestion cards. Ties on
     * [ChatMessageEntity.timestamp] (same-millisecond inserts) break on the autoincrement id.
     */
    @Query("SELECT * FROM chat_messages WHERE role = 'assistant' ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun latestAssistantMessage(): ChatMessageEntity?

    /** Rewrites (or, with nulls, clears) the suggestion envelope stored on one message row. */
    @Query(
        "UPDATE chat_messages SET suggestionsJson = :suggestionsJson, " +
            "suggestionSessionId = :suggestionSessionId WHERE id = :id"
    )
    suspend fun updateSuggestions(id: Long, suggestionsJson: String?, suggestionSessionId: String?)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAll()
}
