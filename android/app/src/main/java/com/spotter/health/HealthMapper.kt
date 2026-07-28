package com.spotter.health

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The Health Connect exercise types Spotter mirrors. Deliberately a small local enum rather than the
 * SDK's `ExerciseSessionRecord.EXERCISE_TYPE_*` ints so [HealthMapper] stays a pure-JVM module that
 * can be unit-tested without loading any Health Connect class; [HealthConnectManager] does the
 * one-line translation to the SDK constants.
 */
enum class HealthExerciseType { STRENGTH_TRAINING, RUNNING, WALKING }

/** A Health Connect `ExerciseSessionRecord` reduced to the fields Spotter actually supplies. */
data class ExerciseSessionInput(
    val start: Instant,
    val end: Instant,
    val type: HealthExerciseType,
    val title: String,
)

/**
 * A Health Connect `WeightRecord` reduced to what Spotter supplies. [pounds] is Spotter's canonical
 * bodyweight unit (the server's bounds are lb); the SDK's `Mass.pounds(...)` conversion happens in
 * [HealthConnectManager] so this stays SDK-free.
 */
data class WeightInput(
    val time: Instant,
    val pounds: Double,
)

/**
 * Pure mapping from Spotter's domain rows to Health Connect record inputs — **all** time, type and
 * unit logic for the health mirror lives here, so it is testable without the SDK (the
 * `WorkoutNudge` precedent: decision logic pure, plumbing separate).
 *
 * Every function returns `null` for anything Health Connect would reject or that carries no
 * information (unknown timing, non-positive duration, non-positive weight) — the caller treats a
 * null as "nothing to mirror" and drops it silently.
 */
object HealthMapper {

    /** Local time-of-day a date-only row is anchored at, so it lands on the intended calendar day. */
    private val DAY_ANCHOR: LocalTime = LocalTime.NOON

    /**
     * A finished strength session.
     *
     * @param startedAtMs the locally stamped wall-clock start (epoch millis). Null for sessions
     *   created before that column existed — then the session is anchored at local noon on [date].
     * @param date the session's ISO date (`yyyy-MM-dd`); the fallback anchor.
     * @param durationSeconds the session's logged duration. Null or non-positive ⇒ null (a
     *   zero-length record is rejected by Health Connect and says nothing anyway).
     * @param routineName the routine the session ran, used as the record title.
     */
    fun strengthSession(
        startedAtMs: Long?,
        date: String,
        durationSeconds: Int?,
        routineName: String?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ExerciseSessionInput? {
        val duration = durationSeconds?.takeIf { it > 0 } ?: return null
        val start = startedAtMs?.let { Instant.ofEpochMilli(it) }
            ?: dayAnchor(date, zone)
            ?: return null
        return ExerciseSessionInput(
            start = start,
            end = start.plusSeconds(duration.toLong()),
            type = HealthExerciseType.STRENGTH_TRAINING,
            title = routineName?.takeIf { it.isNotBlank() } ?: "Workout",
        )
    }

    /**
     * A finished cardio session — guided (Couch to 5K / Free Run), or a manual after-the-fact entry.
     *
     * The end is preferentially `start + totalElapsedSec` (the actually-tracked activity time, and
     * the only thing a manual entry has: its `startedAt` and `completedAt` are the same noon
     * anchor). Only when no elapsed time was recorded does it fall back to `completedAt`, and then
     * only if that is genuinely after the start.
     *
     * @param activityType `walk`/`run` on a manual entry; null for guided/free runs, which are
     *   running programs — so null maps to [HealthExerciseType.RUNNING].
     */
    fun cardioSession(
        startedAt: String?,
        completedAt: String?,
        totalElapsedSec: Int,
        activityType: String?,
        title: String? = null,
    ): ExerciseSessionInput? {
        val start = parseInstant(startedAt) ?: return null
        val end = when {
            totalElapsedSec > 0 -> start.plusSeconds(totalElapsedSec.toLong())
            else -> parseInstant(completedAt)?.takeIf { it.isAfter(start) }
        } ?: return null
        val type = when (activityType?.lowercase()) {
            "walk" -> HealthExerciseType.WALKING
            else -> HealthExerciseType.RUNNING
        }
        return ExerciseSessionInput(
            start = start,
            end = end,
            type = type,
            title = title?.takeIf { it.isNotBlank() }
                ?: if (type == HealthExerciseType.WALKING) "Walk" else "Run",
        )
    }

    /**
     * A weigh-in. [weightLb] is Spotter's canonical bodyweight unit regardless of the display unit
     * the user entered it in (the UI converts at the edge). The record is anchored at local noon on
     * [date] so it buckets onto the day the user logged, in any timezone.
     */
    fun bodyweight(
        date: String,
        weightLb: Double,
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeightInput? {
        if (weightLb <= 0.0 || !weightLb.isFinite()) return null
        val time = dayAnchor(date, zone) ?: return null
        return WeightInput(time = time, pounds = weightLb)
    }

    /** Local noon on an ISO `yyyy-MM-dd` date, or null when the date can't be parsed. */
    private fun dayAnchor(date: String, zone: ZoneId): Instant? =
        runCatching { LocalDate.parse(date).atTime(DAY_ANCHOR).atZone(zone).toInstant() }.getOrNull()

    /** Lenient ISO-8601 instant parse; null (rather than throwing) on anything unparseable. */
    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching {
                java.time.LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant()
            }.getOrNull()
    }
}
