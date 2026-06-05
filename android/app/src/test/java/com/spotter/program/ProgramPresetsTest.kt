package com.spotter.program

import com.spotter.ui.program.ProgramPresets
import org.junit.Test
import kotlin.test.assertTrue

class ProgramPresetsTest {

    /** Exact seeded exercise names from server migration 0002_seed_exercises.py. */
    private val seededExercises = setOf(
        "Barbell Back Squat", "Barbell Front Squat", "Conventional Deadlift", "Romanian Deadlift",
        "Bench Press", "Incline Bench Press", "Overhead Press", "Barbell Row", "Barbell Curl",
        "Close-Grip Bench Press", "Good Morning", "Rack Pull",
        "Dumbbell Bench Press", "Dumbbell Row", "Dumbbell Shoulder Press",
        "Dumbbell Romanian Deadlift", "Dumbbell Curl", "Dumbbell Lateral Raise",
        "Dumbbell Overhead Tricep Extension", "Goblet Squat", "Dumbbell Reverse Lunge",
        "Push-Up", "Pull-Up", "Dip", "Bodyweight Squat", "Lunge", "Glute Bridge", "Plank",
        "Hollow Hold", "Mountain Climber",
        "Lat Pulldown", "Seated Cable Row", "Cable Curl", "Leg Press", "Leg Curl", "Leg Extension",
    )

    @Test
    fun `every preset exercise references a seeded exercise name`() {
        ProgramPresets.all.forEach { preset ->
            preset.days.forEach { day ->
                day.exercises.forEach { ex ->
                    assertTrue(
                        ex.name in seededExercises,
                        "Preset '${preset.displayName}' references unknown exercise '${ex.name}'",
                    )
                }
            }
        }
    }

    @Test
    fun `every preset has at least one training day with exercises`() {
        ProgramPresets.all.forEach { preset ->
            assertTrue(
                preset.days.any { it.exercises.isNotEmpty() },
                "Preset '${preset.displayName}' has no exercises",
            )
        }
    }

    @Test
    fun `preset ids are unique`() {
        val ids = ProgramPresets.all.map { it.id }
        assertTrue(ids.size == ids.toSet().size, "Duplicate preset id found")
    }
}
