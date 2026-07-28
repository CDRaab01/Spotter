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
    // Program-structure fields (mirror of ProgramOut; server-computed current_week/is_deload_week
    // are deliberately NOT mirrored — they'd go stale in a cache).
    val source: String = "manual",
    val description: String? = null,
    val weeks: Int? = null,
    val deloadWeek: Int? = null,
    val startedOn: String? = null,
    val serverId: String? = null,
    val syncPending: Boolean = false,
    val pendingDelete: Boolean = false,
)
