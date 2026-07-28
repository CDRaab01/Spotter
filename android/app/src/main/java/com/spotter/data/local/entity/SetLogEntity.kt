package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "set_logs")
data class SetLogEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val exerciseId: String,
    val setNumber: Int,
    val reps: Int,
    val weight: Double?,
    val completed: Boolean,
    val completedAt: String?,
    val exerciseName: String? = null,
    val targetSets: Int? = null,
    val targetReps: Int? = null,
    val targetWeight: Double? = null,
    val supersetGroup: Int? = null,
    val rpe: Double? = null,             // 1.0–10.0, one decimal; null when not tracked
    val setType: String = "normal",      // normal | warmup | drop | failure | amrap
    val serverId: String? = null,        // server-assigned UUID after sync
    val syncPending: Boolean = false,    // true when modified offline
    /**
     * Deletion tombstone (the routine/program pendingDelete precedent applied to sets): a set
     * deleted while the server DELETE couldn't land stays as a hidden row until the sync drain
     * deletes it server-side. Without it, the getSession merge would re-add the server's copy
     * ("server has a set we don't have locally") and resurrect every offline deletion.
     */
    val pendingDelete: Boolean = false,
)
