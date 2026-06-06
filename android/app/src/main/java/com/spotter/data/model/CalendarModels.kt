package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CalendarEntry(
    @SerialName("session_id") val sessionId: String,
    val date: String,
    @SerialName("routine_name") val routineName: String? = null,
    val status: String,
    @SerialName("set_count") val setCount: Int,
)
