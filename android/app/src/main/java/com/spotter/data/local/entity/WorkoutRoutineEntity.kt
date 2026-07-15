package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_routines")
data class WorkoutRoutineEntity(
    // Stable local id — a UUID for a routine created offline, or the server id for one pulled from
    // the server. Never changes once assigned, so sessions/programs that reference it stay valid;
    // the server id is tracked separately in [serverId] and used only when talking to the API.
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val source: String,
    val createdAt: String,
    // The server's id once this routine has been accepted; null while it only exists locally.
    val serverId: String? = null,
    // True when the routine has local edits (create/rename/exercise change) not yet pushed.
    val syncPending: Boolean = false,
    // True when deleted offline: hidden from reads, the delete pushed on the next drain.
    val pendingDelete: Boolean = false,
)
