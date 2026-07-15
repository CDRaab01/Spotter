package com.spotter.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "body_metrics")
data class BodyMetricEntity(
    // Local UUID for a weigh-in logged offline; the server id once acknowledged (so a synced row's
    // PK equals its server id and a later pull REPLACEs it cleanly instead of duplicating).
    @PrimaryKey val id: String,
    val userId: String,
    val date: String,
    val weight: Double,
    val bodyfat: Double?,
    // Optional tape measurements (in/cm as entered) — null on an ordinary weigh-in.
    val neck: Double? = null,
    val chest: Double? = null,
    val waist: Double? = null,
    val hips: Double? = null,
    val arm: Double? = null,
    val thigh: Double? = null,
    // The server's id once the row has been accepted; null while it only exists locally.
    val serverId: String? = null,
    // True for a weigh-in created offline that hasn't been pushed yet — the drain queue.
    val syncPending: Boolean = false,
)
