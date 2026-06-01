package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExerciseOut(
    val id: String,
    val name: String,
    @SerialName("muscle_group") val muscleGroup: String? = null,
    val equipment: String? = null,
)
