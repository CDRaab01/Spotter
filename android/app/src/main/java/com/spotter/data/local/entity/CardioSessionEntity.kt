package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room mirror of a cardio session. Like [WorkoutSessionEntity], the server is the source of truth
 * and this is a local cache that also enables offline start + Resume across process death.
 */
@Entity(tableName = "cardio_sessions")
data class CardioSessionEntity(
    @PrimaryKey val id: String,           // local UUID
    val serverId: String? = null,         // server-assigned UUID after sync
    val programId: String,
    val weekNumber: Int? = null,
    val dayNumber: Int? = null,
    val startedAt: String,                // ISO-8601
    val completedAt: String? = null,      // ISO-8601
    val status: String,                   // in_progress | completed | abandoned
    val totalElapsedSec: Int = 0,
    val activityType: String? = null,     // walk | run for manual entries; null for guided/free
    val distanceMeters: Int? = null,      // optional canonical distance (meters)
    val syncPending: Boolean = false,     // created/updated offline, not yet pushed
)
