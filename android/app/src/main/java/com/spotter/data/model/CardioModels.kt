package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cardio domain + transport models.
 *
 * The *program definitions* (Couch to 5K, Free Run) are static and bundled client-side — see
 * [com.spotter.ui.cardio.CardioPrograms]. Only the user's [CardioSession] records are persisted
 * (server + Room mirror), mirroring how strength workouts are stored.
 */

/** A phase within a cardio interval. [isWork] drives the brighter interval-bar shade. */
enum class CardioPhase(val label: String, val isWork: Boolean) {
    WARM_UP("Warm up", false),
    RUN("Run", true),
    WALK("Walk", false),
    COOL_DOWN("Cool down", false),
}

enum class CardioProgramType { GUIDED, FREE }

/** A single timed block of a session. */
data class Interval(
    val phase: CardioPhase,
    val durationSec: Int,
)

data class CardioDay(
    val dayNumber: Int,
    val totalDurationSec: Int,
    val intervals: List<Interval>,
)

data class CardioWeek(
    val weekNumber: Int,
    val intro: String,
    val days: List<CardioDay>,
)

data class CardioProgram(
    val id: String,
    val name: String,
    val type: CardioProgramType,
    val description: String,
    /** Null for [CardioProgramType.FREE]; intervals are built at runtime from user config. */
    val weeks: List<CardioWeek>? = null,
)

// ---------------------------------------------------------------------------
// Transport DTOs (mirror server app/schemas/cardio.py)
// ---------------------------------------------------------------------------

@Serializable
data class CardioSessionCreate(
    @SerialName("program_id") val programId: String,
    @SerialName("week_number") val weekNumber: Int? = null,
    @SerialName("day_number") val dayNumber: Int? = null,
)

@Serializable
data class CardioSessionUpdate(
    val status: String? = null,
    @SerialName("total_elapsed_sec") val totalElapsedSec: Int? = null,
)

/**
 * Log a walk/run after the fact — the server creates a *completed* session directly. Distance is
 * canonical whole meters (converted from the user's unit at the edge); [date] is a plain ISO date.
 */
@Serializable
data class CardioManualCreate(
    @SerialName("activity_type") val activityType: String,
    @SerialName("duration_sec") val durationSec: Int,
    @SerialName("distance_meters") val distanceMeters: Int? = null,
    val date: String? = null,
)

@Serializable
data class CardioSessionOut(
    val id: String,
    @SerialName("program_id") val programId: String,
    @SerialName("week_number") val weekNumber: Int? = null,
    @SerialName("day_number") val dayNumber: Int? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    val status: String,
    @SerialName("total_elapsed_sec") val totalElapsedSec: Int = 0,
    @SerialName("activity_type") val activityType: String? = null,
    @SerialName("distance_meters") val distanceMeters: Int? = null,
)

/** Manual-entry activity types, shared as constants so client and server agree. */
object CardioActivityType {
    const val WALK = "walk"
    const val RUN = "run"
}

/** Lifecycle states, shared as constants so client and server agree. */
object CardioStatus {
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
    const val ABANDONED = "abandoned"
}
