package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BodyMetricCreate(
    val date: String,
    val weight: Double,
    val bodyfat: Double? = null,
)

@Serializable
data class BodyMetricOut(
    val id: String,
    @SerialName("user_id") val userId: String,
    val date: String,
    val weight: Double,
    val bodyfat: Double? = null,
)
