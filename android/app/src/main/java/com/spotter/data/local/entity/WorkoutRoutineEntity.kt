package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_routines")
data class WorkoutRoutineEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val source: String,
    val createdAt: String,
)
