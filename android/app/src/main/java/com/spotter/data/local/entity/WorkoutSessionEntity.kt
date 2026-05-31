package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val planId: String?,
    val date: String,
    val status: String,
    val durationSeconds: Int?,
    val note: String?,
)
