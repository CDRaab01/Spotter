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
)
