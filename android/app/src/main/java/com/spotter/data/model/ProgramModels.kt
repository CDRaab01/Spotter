package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProgramDayIn(
    @SerialName("plan_id") val planId: String? = null,
    val label: String,
    val order: Int = 0,
)

@Serializable
data class ProgramCreate(
    val name: String,
    val days: List<ProgramDayIn> = emptyList(),
)

@Serializable
data class ProgramUpdate(
    val name: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class ProgramDaysUpdate(val days: List<ProgramDayIn>)

@Serializable
data class ProgramDayOut(
    val id: String,
    @SerialName("plan_id") val planId: String? = null,
    val label: String,
    val order: Int = 0,
    @SerialName("plan_name") val planName: String? = null,
)

@Serializable
data class ProgramOut(
    val id: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean = false,
    val days: List<ProgramDayOut> = emptyList(),
)
