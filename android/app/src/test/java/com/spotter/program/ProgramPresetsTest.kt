package com.spotter.program

import com.spotter.ui.program.PresetDay
import com.spotter.ui.program.PresetExercise
import com.spotter.ui.program.PresetProgram
import com.spotter.ui.program.ProgramPresets
import com.spotter.ui.program.presetCadenceLine
import com.spotter.ui.program.restDay
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProgramPresetsTest {

    /** Exact seeded exercise names from server migrations 0002 + 0009. */
    private val seededExercises = setOf(
        // 0002_seed_exercises.py
        "Barbell Back Squat", "Barbell Front Squat", "Conventional Deadlift", "Romanian Deadlift",
        "Bench Press", "Incline Bench Press", "Overhead Press", "Barbell Row", "Barbell Curl",
        "Close-Grip Bench Press", "Good Morning", "Rack Pull",
        "Dumbbell Bench Press", "Dumbbell Row", "Dumbbell Shoulder Press",
        "Dumbbell Romanian Deadlift", "Dumbbell Curl", "Dumbbell Lateral Raise",
        "Dumbbell Overhead Tricep Extension", "Goblet Squat", "Dumbbell Reverse Lunge",
        "Push-Up", "Pull-Up", "Dip", "Bodyweight Squat", "Lunge", "Glute Bridge", "Plank",
        "Hollow Hold", "Mountain Climber",
        "Lat Pulldown", "Seated Cable Row", "Cable Curl", "Leg Press", "Leg Curl", "Leg Extension",
        // 0009_seed_accessory_exercises.py
        "Decline Bench Press", "Dumbbell Incline Press", "Dumbbell Fly", "Cable Crossover", "Pec Deck",
        "Chin-Up", "Inverted Row", "T-Bar Row", "Chest-Supported Row", "Straight-Arm Pulldown",
        "Dumbbell Shrug", "Arnold Press", "Dumbbell Front Raise", "Cable Lateral Raise",
        "Rear Delt Fly", "Face Pull", "Upright Row", "Pike Push-Up",
        "Hammer Curl", "Incline Dumbbell Curl", "Preacher Curl", "Concentration Curl",
        "Tricep Pushdown", "Overhead Cable Tricep Extension", "Skull Crusher", "Tricep Kickback",
        "Bench Dip", "Bulgarian Split Squat", "Walking Lunge", "Step-Up", "Hack Squat", "Box Squat",
        "Hip Thrust", "Cable Glute Kickback", "Seated Leg Curl", "Nordic Curl",
        "Standing Calf Raise", "Seated Calf Raise", "Dumbbell Calf Raise",
        "Hanging Leg Raise", "Cable Crunch", "Russian Twist", "Bicycle Crunch", "Crunch",
        "Ab Wheel Rollout",
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
    fun `every preset schedules at least one rest day`() {
        // Without rest days in the cycle a "3x a week" preset actually trains every single day —
        // the prescribed cadence has to exist in the days list to be real.
        ProgramPresets.all.forEach { preset ->
            assertTrue(
                preset.days.any { it.isRest },
                "Preset '${preset.displayName}' has no rest day in its cycle",
            )
        }
    }

    @Test
    fun `rest days are labelled and carry no exercises`() {
        ProgramPresets.all.forEach { preset ->
            preset.days.filter { it.isRest }.forEach { day ->
                assertTrue(
                    day.label.isNotBlank(),
                    "A rest day in '${preset.displayName}' has no label",
                )
                assertTrue(day.exercises.isEmpty())
            }
        }
    }

    @Test
    fun `every preset trains between 2 and 6 times a week`() {
        ProgramPresets.all.forEach { preset ->
            val perWeek = preset.trainingDays.size * 7.0 / preset.days.size
            assertTrue(
                perWeek in 2.0..6.0,
                "Preset '${preset.displayName}' prescribes $perWeek sessions a week",
            )
        }
    }

    @Test
    fun `cadence line is derived from the cycle`() {
        val preset = PresetProgram(
            id = "x",
            displayName = "X",
            description = "d",
            days = listOf(
                PresetDay("Day A", listOf(PresetExercise("Bench Press", 5, 5, weight = 95.0))),
                restDay(),
                PresetDay("Day B", listOf(PresetExercise("Barbell Row", 5, 5, weight = 95.0))),
                restDay(),
            ),
        )
        assertEquals("2 workouts in a 4-day cycle · about 3.5 a week", presetCadenceLine(preset))
    }

    @Test
    fun `preset ids are unique`() {
        val ids = ProgramPresets.all.map { it.id }
        assertTrue(ids.size == ids.toSet().size, "Duplicate preset id found")
    }

    @Test
    fun `weighted preset exercises carry a starting weight, bodyweight ones none`() {
        ProgramPresets.all.forEach { preset ->
            preset.days.forEach { day ->
                day.exercises.forEach { ex ->
                    if (ex.isBodyweight) {
                        assertTrue(
                            ex.weight == null,
                            "Bodyweight '${ex.name}' in '${preset.displayName}' shouldn't set a weight",
                        )
                    } else {
                        // A barbell alone is 45 lb — a weighted movement with no target
                        // renders as "BW" in workout mode, which is wrong and unhelpful.
                        assertTrue(
                            ex.weight != null && ex.weight > 0.0,
                            "Weighted '${ex.name}' in '${preset.displayName}' needs a starting weight",
                        )
                    }
                }
            }
        }
    }
}
