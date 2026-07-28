package com.spotter.health

import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the pure mapping from Spotter rows to Health Connect record inputs — the whole point of
 * [HealthMapper] being SDK-free is that this runs as a plain JVM test.
 */
class HealthMapperTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    // ── Strength ──────────────────────────────────────────────────────────────

    @Test
    fun `strength session spans the logged duration from the start anchor`() {
        val startedAt = Instant.parse("2026-07-28T17:05:00Z")
        val input = HealthMapper.strengthSession(
            startedAtMs = startedAt.toEpochMilli(),
            date = "2026-07-28",
            durationSeconds = 45 * 60,
            routineName = "Push Day",
            zone = utc,
        )

        requireNotNull(input)
        assertEquals(startedAt, input.start)
        assertEquals(Instant.parse("2026-07-28T17:50:00Z"), input.end)
        assertEquals(HealthExerciseType.STRENGTH_TRAINING, input.type)
        assertEquals("Push Day", input.title)
    }

    @Test
    fun `strength session without a start anchor falls back to local noon on its date`() {
        val input = HealthMapper.strengthSession(
            startedAtMs = null,
            date = "2026-07-28",
            durationSeconds = 600,
            routineName = null,
            zone = utc,
        )

        requireNotNull(input)
        assertEquals(Instant.parse("2026-07-28T12:00:00Z"), input.start)
        assertEquals(Instant.parse("2026-07-28T12:10:00Z"), input.end)
        // No routine name (an ad-hoc session) still gets a usable title.
        assertEquals("Workout", input.title)
    }

    @Test
    fun `strength session anchors noon in the caller's zone, not UTC`() {
        val zone = ZoneId.of("America/New_York")
        val input = HealthMapper.strengthSession(
            startedAtMs = null,
            date = "2026-07-28",
            durationSeconds = 60,
            routineName = "Legs",
            zone = zone,
        )

        requireNotNull(input)
        assertEquals(LocalDate.parse("2026-07-28").atTime(12, 0).atZone(zone).toInstant(), input.start)
    }

    @Test
    fun `strength session with no usable duration is dropped`() {
        val args = listOf<Int?>(null, 0, -30)
        for (duration in args) {
            assertNull(
                HealthMapper.strengthSession(
                    startedAtMs = 1_700_000_000_000L,
                    date = "2026-07-28",
                    durationSeconds = duration,
                    routineName = "Push Day",
                    zone = utc,
                ),
                "duration=$duration should not map to a record",
            )
        }
    }

    @Test
    fun `strength session with an unparseable date and no anchor is dropped`() {
        assertNull(
            HealthMapper.strengthSession(
                startedAtMs = null,
                date = "not-a-date",
                durationSeconds = 600,
                routineName = "Push Day",
                zone = utc,
            ),
        )
    }

    // ── Cardio ────────────────────────────────────────────────────────────────

    @Test
    fun `guided run uses the tracked elapsed time and maps to running`() {
        val input = HealthMapper.cardioSession(
            startedAt = "2026-07-28T06:00:00Z",
            completedAt = "2026-07-28T06:40:00Z",
            totalElapsedSec = 1_800,
            // Guided/free runs carry no activity type — they're running programs.
            activityType = null,
        )

        requireNotNull(input)
        assertEquals(Instant.parse("2026-07-28T06:00:00Z"), input.start)
        // 30 min of tracked running, not the 40 min of wall clock that includes the pauses.
        assertEquals(Instant.parse("2026-07-28T06:30:00Z"), input.end)
        assertEquals(HealthExerciseType.RUNNING, input.type)
        assertEquals("Run", input.title)
    }

    @Test
    fun `manual walk maps to walking off its noon anchor`() {
        val input = HealthMapper.cardioSession(
            // A manual entry's start and completion are the same noon anchor.
            startedAt = "2026-07-28T12:00:00Z",
            completedAt = "2026-07-28T12:00:00Z",
            totalElapsedSec = 2_700,
            activityType = "walk",
        )

        requireNotNull(input)
        assertEquals(Instant.parse("2026-07-28T12:00:00Z"), input.start)
        assertEquals(Instant.parse("2026-07-28T12:45:00Z"), input.end)
        assertEquals(HealthExerciseType.WALKING, input.type)
        assertEquals("Walk", input.title)
    }

    @Test
    fun `manual run activity type is case-insensitive and titles override`() {
        val input = HealthMapper.cardioSession(
            startedAt = "2026-07-28T12:00:00Z",
            completedAt = null,
            totalElapsedSec = 600,
            activityType = "RUN",
            title = "Couch to 5K · Week 3",
        )

        requireNotNull(input)
        assertEquals(HealthExerciseType.RUNNING, input.type)
        assertEquals("Couch to 5K · Week 3", input.title)
    }

    @Test
    fun `cardio with no elapsed time falls back to completedAt`() {
        val input = HealthMapper.cardioSession(
            startedAt = "2026-07-28T06:00:00Z",
            completedAt = "2026-07-28T06:22:00Z",
            totalElapsedSec = 0,
            activityType = null,
        )

        requireNotNull(input)
        assertEquals(Instant.parse("2026-07-28T06:22:00Z"), input.end)
    }

    @Test
    fun `cardio with neither elapsed time nor a later completion is dropped`() {
        assertNull(
            HealthMapper.cardioSession(
                startedAt = "2026-07-28T06:00:00Z",
                completedAt = "2026-07-28T06:00:00Z",
                totalElapsedSec = 0,
                activityType = "run",
            ),
        )
        assertNull(
            HealthMapper.cardioSession(
                startedAt = "2026-07-28T06:00:00Z",
                completedAt = null,
                totalElapsedSec = 0,
                activityType = "run",
            ),
        )
    }

    @Test
    fun `cardio with a missing or unparseable start is dropped`() {
        assertNull(HealthMapper.cardioSession(null, "2026-07-28T06:22:00Z", 600, "run"))
        assertNull(HealthMapper.cardioSession("", "2026-07-28T06:22:00Z", 600, "run"))
        assertNull(HealthMapper.cardioSession("yesterday", "2026-07-28T06:22:00Z", 600, "run"))
    }

    @Test
    fun `cardio accepts an offset timestamp as well as a Z instant`() {
        val input = HealthMapper.cardioSession(
            startedAt = "2026-07-28T02:00:00-04:00",
            completedAt = null,
            totalElapsedSec = 600,
            activityType = "run",
        )

        requireNotNull(input)
        assertEquals(Instant.parse("2026-07-28T06:00:00Z"), input.start)
    }

    // ── Bodyweight ────────────────────────────────────────────────────────────

    @Test
    fun `bodyweight anchors at local noon and stays in pounds`() {
        val input = HealthMapper.bodyweight(date = "2026-07-28", weightLb = 183.4, zone = utc)

        requireNotNull(input)
        assertEquals(Instant.parse("2026-07-28T12:00:00Z"), input.time)
        assertEquals(183.4, input.pounds)
    }

    @Test
    fun `bodyweight rejects non-positive and non-finite values`() {
        assertNull(HealthMapper.bodyweight("2026-07-28", 0.0, utc))
        assertNull(HealthMapper.bodyweight("2026-07-28", -12.0, utc))
        assertNull(HealthMapper.bodyweight("2026-07-28", Double.NaN, utc))
    }

    @Test
    fun `bodyweight with an unparseable date is dropped`() {
        assertNull(HealthMapper.bodyweight("28/07/2026", 183.4, utc))
    }
}
