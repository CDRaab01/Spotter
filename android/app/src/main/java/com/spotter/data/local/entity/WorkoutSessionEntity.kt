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
)
