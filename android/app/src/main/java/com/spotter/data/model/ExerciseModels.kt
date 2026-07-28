package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExerciseOut(
    val id: String,
    val name: String,
    @SerialName("muscle_group") val muscleGroup: String? = null,
    val equipment: String? = null,
    /** How-to text for the exercise detail screen; null on servers that predate it. */
    val instructions: String? = null,
    /** Comma-separated secondary muscle groups, e.g. "triceps, front delts". */
    @SerialName("secondary_muscles") val secondaryMuscles: String? = null,
)
