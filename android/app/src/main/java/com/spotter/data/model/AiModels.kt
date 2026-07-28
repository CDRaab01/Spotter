package com.spotter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatRequest(
    val messages: List<ChatMessage>,
    @SerialName("user_context") val userContext: String? = null,
    @SerialName("current_session_id") val currentSessionId: String? = null,
)

@Serializable
data class SuggestedRoutine(
    val name: String,
    val exercises: List<RoutineExerciseIn>,
)

@Serializable
data class SuggestedProgramDay(
    val label: String,
    val exercises: List<RoutineExerciseIn> = emptyList(),
    val order: Int = 0,
)

@Serializable
data class SuggestedProgram(
    val name: String,
    val days: List<SuggestedProgramDay>,
)

@Serializable
data class AcceptProgramRequest(
    val name: String,
    val days: List<SuggestedProgramDay>,
    // Program-structure extensions (optional; the server defaults them the same way).
    val weeks: Int? = null,
    @SerialName("deload_week") val deloadWeek: Int? = null,
    val description: String? = null,
    val source: String = "ai",
    val activate: Boolean = true,
)

/**
 * One resolved action of an AI-proposed live workout adjustment. Echoed back verbatim
 * to POST /ai/sessions/{id}/adjust when the user taps Apply (the server re-validates).
 */
@Serializable
data class SuggestedAdjustmentAction(
    val type: String, // swap | adjust_weight | remove | add
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("exercise_name") val exerciseName: String,
    @SerialName("new_exercise_id") val newExerciseId: String? = null,
    @SerialName("new_exercise_name") val newExerciseName: String? = null,
    val sets: Int? = null,
    val reps: Int? = null,
    val weight: Double? = null,
    val summary: String = "",
)

@Serializable
data class SuggestedAdjustment(
    val actions: List<SuggestedAdjustmentAction>,
)

@Serializable
data class ApplyAdjustmentRequest(
    val actions: List<SuggestedAdjustmentAction>,
    @SerialName("apply_to_routine") val applyToRoutine: Boolean = true,
)

@Serializable
data class ChatResponse(
    val reply: String,
    @SerialName("suggested_routine") val suggestedRoutine: SuggestedRoutine? = null,
    @SerialName("suggested_program") val suggestedProgram: SuggestedProgram? = null,
    @SerialName("suggested_adjustment") val suggestedAdjustment: SuggestedAdjustment? = null,
)

/**
 * Post-workout coach debrief (POST /ai/sessions/{id}/debrief). Best-effort by design: the
 * summary screen omits the card entirely when the call fails (LM Studio down is normal).
 */
@Serializable
data class DebriefOut(val debrief: String = "")

/** The always-server-computed half of the weekly recap (GET /ai/recap/weekly). */
@Serializable
data class WeeklyRecapStats(
    @SerialName("strength_sessions") val strengthSessions: Int = 0,
    @SerialName("cardio_sessions") val cardioSessions: Int = 0,
    @SerialName("total_volume_lb") val totalVolumeLb: Double = 0.0,
    @SerialName("active_minutes") val activeMinutes: Int = 0,
    val prs: Int = 0,
    /** First vs. last bodyweight in the window; null with fewer than two weigh-ins. */
    @SerialName("bodyweight_delta_lb") val bodyweightDeltaLb: Double? = null,
)

/**
 * GET /ai/recap/weekly — always 200. [narrative] is null when the LLM was unreachable;
 * the numbers are computed server-side and are always present.
 */
@Serializable
data class WeeklyRecapOut(
    @SerialName("week_start") val weekStart: String = "",
    val stats: WeeklyRecapStats = WeeklyRecapStats(),
    val narrative: String? = null,
)

/** One stalled lift from GET /insights (>= the progression engine's stall threshold). */
@Serializable
data class StalledExercise(
    @SerialName("exercise_id") val exerciseId: String = "",
    @SerialName("exercise_name") val exerciseName: String = "",
    @SerialName("sessions_stuck") val sessionsStuck: Int = 0,
    @SerialName("last_weight") val lastWeight: Double? = null,
)

/** GET /insights — proactive coaching signals surfaced on Home. */
@Serializable
data class InsightsOut(
    val stalled: List<StalledExercise> = emptyList(),
    @SerialName("prs_this_week") val prsThisWeek: Int = 0,
)
