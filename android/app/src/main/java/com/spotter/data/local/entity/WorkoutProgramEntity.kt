package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_programs")
data class WorkoutProgramEntity(
    // Stable local id (UUID offline, or the server id once pulled); references from program_days
    // stay valid pre-sync. The server id is tracked in [serverId] and used only against the API.
    @PrimaryKey val id: String,
    val name: String,
    val isActive: Boolean = false,
    val serverId: String? = null,
    val syncPending: Boolean = false,
    val pendingDelete: Boolean = false,
)
