package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "body_metrics")
data class BodyMetricEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val date: String,
    val weight: Double,
    val bodyfat: Double?,
)
