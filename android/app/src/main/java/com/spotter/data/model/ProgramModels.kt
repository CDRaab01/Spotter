package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProgramDayIn(
    @SerialName("routine_id") val routineId: String? = null,
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
    @SerialName("routine_id") val routineId: String? = null,
    val label: String,
    val order: Int = 0,
    @SerialName("routine_name") val routineName: String? = null,
)

@Serializable
data class ProgramOut(
    val id: String,
    val name: String,
    @SerialName("is_active") val isActive: Boolean = false,
    val days: List<ProgramDayOut> = emptyList(),
    // Program-structure fields (2026-07 round). Defaults keep old-server payloads parsing.
    val source: String = "manual",
    val description: String? = null,
    /** Planned length in weeks; null = open-ended. */
    val weeks: Int? = null,
    /** 1-based week that is a deload; null = no scheduled deload. */
    @SerialName("deload_week") val deloadWeek: Int? = null,
    /** ISO date the program was started/activated on. */
    @SerialName("started_on") val startedOn: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    /** Server-computed 1-based current week (from started_on); null when not started. */
    @SerialName("current_week") val currentWeek: Int? = null,
    @SerialName("is_deload_week") val isDeloadWeek: Boolean = false,
)
