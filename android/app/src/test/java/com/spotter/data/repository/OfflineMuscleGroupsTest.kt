package com.spotter.data.repository

import com.spotter.data.local.entity.SetLogEntity
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the offline muscle-group summary to the server's semantics
 * (`server/app/services/session_service.get_session`):
 * completed sets only; unknown/blank muscle groups skipped; volume in kg
 * (`reps × weight_lb × 0.453592`, null/zero weight contributes sets but no volume);
 * one decimal; alphabetical group order.
 */
class OfflineMuscleGroupsTest {

    private fun set(
        exerciseId: String,
        reps: Int = 8,
        weight: Double? = 100.0,
        completed: Boolean = true,
    ) = SetLogEntity(
        id = "$exerciseId-$reps-$weight-$completed-${System.nanoTime()}",
        sessionId = "s1", exerciseId = exerciseId, setNumber = 1,
        reps = reps, weight = weight, completed = completed, completedAt = null,
    )

    private val groups = mapOf(
        "bench" to "chest",
        "squat" to "legs",
        "curl" to "arms",
        "mystery" to null,
        "blank" to "",
    )

    @Test
    fun `aggregates completed sets per group with kg volume like the server`() {
        val result = OfflineMuscleGroups.summarize(
            listOf(
                set("bench", reps = 8, weight = 100.0),
                set("bench", reps = 8, weight = 100.0),
                set("squat", reps = 5, weight = 225.0),
            ),
            groups,
        )

        assertEquals(listOf("chest", "legs"), result.map { it.muscleGroup })
        val chest = result.first { it.muscleGroup == "chest" }
        assertEquals(2, chest.sets)
        // 2 × (8 × 100 lb × 0.453592) = 725.7472 kg → one decimal.
        assertEquals(725.7f, chest.volume)
        val legs = result.first { it.muscleGroup == "legs" }
        assertEquals(1, legs.sets)
        // 5 × 225 × 0.453592 = 510.291 → 510.3.
        assertEquals(510.3f, legs.volume)
    }

    @Test
    fun `incomplete sets do not count`() {
        val result = OfflineMuscleGroups.summarize(
            listOf(set("bench", completed = false), set("bench", completed = true)),
            groups,
        )
        assertEquals(1, result.single().sets)
    }

    @Test
    fun `bodyweight and zero-weight sets count sets but add no volume`() {
        val result = OfflineMuscleGroups.summarize(
            listOf(
                set("curl", reps = 12, weight = null),
                // Server-side Python truthiness (`if sl.weight:`) also excludes an exact 0.
                set("curl", reps = 12, weight = 0.0),
            ),
            groups,
        )
        val arms = result.single()
        assertEquals(2, arms.sets)
        assertEquals(0f, arms.volume)
    }

    @Test
    fun `exercises with no known muscle group drop out entirely`() {
        val result = OfflineMuscleGroups.summarize(
            listOf(
                set("mystery"),              // null muscle group in the mirror
                set("blank"),                // blank muscle group
                set("not-in-mirror"),        // absent from the mirror
                set("bench"),
            ),
            groups,
        )
        assertEquals(listOf("chest"), result.map { it.muscleGroup })
    }

    @Test
    fun `groups sort alphabetically`() {
        val result = OfflineMuscleGroups.summarize(
            listOf(set("squat"), set("curl"), set("bench")),
            groups,
        )
        assertEquals(listOf("arms", "chest", "legs"), result.map { it.muscleGroup })
    }

    @Test
    fun `empty input yields an empty summary`() {
        assertTrue(OfflineMuscleGroups.summarize(emptyList(), groups).isEmpty())
    }
}
