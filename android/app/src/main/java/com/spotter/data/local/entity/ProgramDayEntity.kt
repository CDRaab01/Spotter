package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "program_days")
data class ProgramDayEntity(
    @PrimaryKey val id: String,
    val programId: String,
    val routineId: String?,
    val label: String,
    val order: Int = 0,
    val routineName: String? = null,
)
