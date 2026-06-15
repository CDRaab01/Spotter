package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val routineId: String?,
    val date: String,
    val status: String,
    val durationSeconds: Int?,
    val note: String?,
    val exerciseNotes: String? = null,   // JSON Map<String,String>
    val serverId: String? = null,         // server-assigned UUID after sync
    val syncPending: Boolean = false,     // true when created/updated offline
    // Wall-clock start of the session (epoch millis), stamped locally when a session goes
    // in_progress so the in-progress banner/notification can show a live elapsed clock. Local
    // only — the server doesn't track it; null for sessions created before this column existed.
    val startedAtMs: Long? = null,
)
