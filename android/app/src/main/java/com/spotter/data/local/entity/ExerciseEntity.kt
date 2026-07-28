package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room mirror of the server's seeded exercise catalog (`GET /exercises`), refreshed
 * opportunistically by the Home sync round / reconnect observer and as a side effect of every
 * online search. Powers offline Exercise Library search, offline preset name→id resolution, and
 * the offline muscle-group summary for offline-finished workouts. Ids are the server's — the
 * catalog is server-seeded and read-only on the client, so there is no local-id/serverId split.
 */
@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val muscleGroup: String?,
    val equipment: String?,
    val instructions: String? = null,
    val secondaryMuscles: String? = null, // comma-separated, mirrors ExerciseOut
)
