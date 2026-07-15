package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BodyMetricCreate(
    val date: String,
    val weight: Double,
    val bodyfat: Double? = null,
    // Optional tape measurements, stored in the user's chosen length unit as entered
    // (in/cm). All null on an ordinary weigh-in.
    val neck: Double? = null,
    val chest: Double? = null,
    val waist: Double? = null,
    val hips: Double? = null,
    val arm: Double? = null,
    val thigh: Double? = null,
)

@Serializable
data class BodyMetricOut(
    val id: String,
    @SerialName("user_id") val userId: String,
    val date: String,
    val weight: Double,
    val bodyfat: Double? = null,
    val neck: Double? = null,
    val chest: Double? = null,
    val waist: Double? = null,
    val hips: Double? = null,
    val arm: Double? = null,
    val thigh: Double? = null,
)
