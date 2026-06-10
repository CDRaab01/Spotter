package com.spotter.ui.program

/**
 * Built-in starter programs the user can apply in one tap. Each preset references
 * exercises by their exact seeded name (see server migration 0002_seed_exercises);
 * on apply they are resolved to exercise IDs and persisted via the existing
 * `POST /ai/programs/accept` flow.
 *
 * Weighted movements carry a conservative starting load (lb) so the first session
 * has a real target instead of rendering as bodyweight — a barbell alone is 45 lb.
 * They're learn-the-movement weights; the user adjusts in-session and the
 * suggested-weight progression takes over after.
 */
data class PresetExercise(
    val name: String,
    val sets: Int,
    val reps: Int,
    val isBodyweight: Boolean = false,
    /** Starting weight in lb; null only for bodyweight movements. */
    val weight: Double? = null,
)

data class PresetDay(
    val label: String,
    val exercises: List<PresetExercise>,
)

data class PresetProgram(
    val id: String,
    val displayName: String,
    val description: String,
    val days: List<PresetDay>,
)

object ProgramPresets {

    val all: List<PresetProgram> = listOf(
        PresetProgram(
            id = "stronglifts_5x5",
            displayName = "StrongLifts 5×5",
            description = "Classic barbell strength. Alternate Day A / Day B, ~3×/week. " +
                "Add weight each session.",
            days = listOf(
                PresetDay(
                    "Day A",
                    listOf(
                        PresetExercise("Barbell Back Squat", 5, 5, weight = 95.0),
                        PresetExercise("Bench Press", 5, 5, weight = 95.0),
                        PresetExercise("Barbell Row", 5, 5, weight = 95.0),
                    ),
                ),
                PresetDay(
                    "Day B",
                    listOf(
                        PresetExercise("Barbell Back Squat", 5, 5, weight = 95.0),
                        PresetExercise("Overhead Press", 5, 5, weight = 65.0),
                        PresetExercise("Conventional Deadlift", 1, 5, weight = 135.0),
                    ),
                ),
            ),
        ),
        PresetProgram(
            id = "ppl",
            displayName = "Push / Pull / Legs",
            description = "A 3-day hypertrophy split hitting each muscle group with " +
                "compound-first volume. Run once or twice per week.",
            days = listOf(
                PresetDay(
                    "Push",
                    listOf(
                        PresetExercise("Bench Press", 4, 8, weight = 95.0),
                        PresetExercise("Overhead Press", 3, 8, weight = 65.0),
                        PresetExercise("Incline Bench Press", 3, 10, weight = 75.0),
                        PresetExercise("Dumbbell Lateral Raise", 3, 15, weight = 10.0),
                        PresetExercise("Dumbbell Overhead Tricep Extension", 3, 12, weight = 25.0),
                        PresetExercise("Dip", 3, 10, isBodyweight = true),
                    ),
                ),
                PresetDay(
                    "Pull",
                    listOf(
                        PresetExercise("Pull-Up", 4, 8, isBodyweight = true),
                        PresetExercise("Barbell Row", 4, 8, weight = 95.0),
                        PresetExercise("Lat Pulldown", 3, 12, weight = 90.0),
                        PresetExercise("Barbell Curl", 3, 10, weight = 45.0),
                        PresetExercise("Dumbbell Curl", 3, 12, weight = 20.0),
                    ),
                ),
                PresetDay(
                    "Legs",
                    listOf(
                        PresetExercise("Barbell Back Squat", 4, 8, weight = 95.0),
                        PresetExercise("Romanian Deadlift", 3, 10, weight = 95.0),
                        PresetExercise("Leg Press", 3, 12, weight = 180.0),
                        PresetExercise("Leg Curl", 3, 12, weight = 70.0),
                        PresetExercise("Leg Extension", 3, 15, weight = 70.0),
                    ),
                ),
            ),
        ),
        PresetProgram(
            id = "upper_lower",
            displayName = "Upper / Lower",
            description = "A 4-day split alternating upper- and lower-body days. " +
                "Balanced strength and size.",
            days = listOf(
                PresetDay(
                    "Upper",
                    listOf(
                        PresetExercise("Bench Press", 4, 6, weight = 95.0),
                        PresetExercise("Barbell Row", 4, 8, weight = 95.0),
                        PresetExercise("Overhead Press", 3, 8, weight = 65.0),
                        PresetExercise("Pull-Up", 3, 8, isBodyweight = true),
                        PresetExercise("Barbell Curl", 3, 10, weight = 45.0),
                        PresetExercise("Close-Grip Bench Press", 3, 10, weight = 75.0),
                    ),
                ),
                PresetDay(
                    "Lower",
                    listOf(
                        PresetExercise("Barbell Back Squat", 4, 6, weight = 95.0),
                        PresetExercise("Romanian Deadlift", 3, 8, weight = 95.0),
                        PresetExercise("Leg Press", 3, 12, weight = 180.0),
                        PresetExercise("Leg Curl", 3, 12, weight = 70.0),
                        PresetExercise("Leg Extension", 3, 15, weight = 70.0),
                    ),
                ),
            ),
        ),
        PresetProgram(
            id = "full_body",
            displayName = "Full Body (Beginner)",
            description = "One balanced full-body session, run ~3×/week. The simplest " +
                "way to build a base of strength.",
            days = listOf(
                PresetDay(
                    "Full Body",
                    listOf(
                        PresetExercise("Barbell Back Squat", 3, 8, weight = 95.0),
                        PresetExercise("Bench Press", 3, 8, weight = 95.0),
                        PresetExercise("Barbell Row", 3, 8, weight = 95.0),
                        PresetExercise("Overhead Press", 3, 10, weight = 65.0),
                        PresetExercise("Romanian Deadlift", 3, 10, weight = 95.0),
                    ),
                ),
            ),
        ),
        PresetProgram(
            id = "dumbbell_only",
            displayName = "Dumbbell Only",
            description = "A full-body home routine needing only dumbbells. Great for " +
                "training without a barbell or machines.",
            days = listOf(
                PresetDay(
                    "Day A",
                    listOf(
                        PresetExercise("Dumbbell Bench Press", 4, 10, weight = 30.0),
                        PresetExercise("Dumbbell Row", 4, 10, weight = 35.0),
                        PresetExercise("Goblet Squat", 4, 12, weight = 35.0),
                        PresetExercise("Dumbbell Shoulder Press", 3, 12, weight = 25.0),
                        PresetExercise("Dumbbell Curl", 3, 12, weight = 20.0),
                    ),
                ),
                PresetDay(
                    "Day B",
                    listOf(
                        PresetExercise("Dumbbell Romanian Deadlift", 4, 10, weight = 30.0),
                        PresetExercise("Dumbbell Reverse Lunge", 3, 12, weight = 20.0),
                        PresetExercise("Dumbbell Shoulder Press", 3, 10, weight = 25.0),
                        PresetExercise("Dumbbell Lateral Raise", 3, 15, weight = 10.0),
                        PresetExercise("Dumbbell Overhead Tricep Extension", 3, 12, weight = 25.0),
                    ),
                ),
            ),
        ),
        // ── Special-case programs ─────────────────────────────────────────────
        // Curated around a specific constraint. Not medical advice: each description
        // tells the user to get clearance from their doctor/physio first, and the
        // exercise selection avoids the commonly contraindicated patterns for that
        // case (they can still swap movements via the per-day Edit screen).
        PresetProgram(
            id = "knee_friendly",
            displayName = "Knee-Friendly Strength",
            description = "Train around a cranky knee: upper-body strength plus " +
                "knee-sparing glute and hamstring work — no squats, lunges, or leg " +
                "extensions. Stay in pain-free range, and get cleared by your doctor " +
                "or physio first.",
            days = listOf(
                PresetDay(
                    "Upper Push",
                    listOf(
                        PresetExercise("Dumbbell Bench Press", 3, 10, weight = 30.0),
                        PresetExercise("Dumbbell Shoulder Press", 3, 10, weight = 25.0),
                        PresetExercise("Dumbbell Incline Press", 3, 10, weight = 25.0),
                        PresetExercise("Dumbbell Lateral Raise", 3, 15, weight = 10.0),
                        PresetExercise("Tricep Pushdown", 3, 12, weight = 40.0),
                    ),
                ),
                PresetDay(
                    "Upper Pull",
                    listOf(
                        PresetExercise("Lat Pulldown", 3, 10, weight = 90.0),
                        PresetExercise("Seated Cable Row", 3, 10, weight = 70.0),
                        PresetExercise("Chest-Supported Row", 3, 10, weight = 25.0),
                        PresetExercise("Face Pull", 3, 15, weight = 30.0),
                        PresetExercise("Hammer Curl", 3, 12, weight = 20.0),
                    ),
                ),
                PresetDay(
                    "Hips & Hamstrings",
                    listOf(
                        PresetExercise("Glute Bridge", 3, 12, isBodyweight = true),
                        PresetExercise("Hip Thrust", 3, 10, weight = 95.0),
                        PresetExercise("Seated Leg Curl", 3, 12, weight = 60.0),
                        PresetExercise("Dumbbell Romanian Deadlift", 3, 10, weight = 30.0),
                        PresetExercise("Standing Calf Raise", 3, 15, isBodyweight = true),
                    ),
                ),
            ),
        ),
        PresetProgram(
            id = "prenatal_late_term",
            displayName = "Prenatal — Third Trimester",
            description = "Light full-body strength for late pregnancy: seated or " +
                "standing work only, nothing lying flat on your back, no breath-holding " +
                "or core flexion. Keep loads light and stop anything that feels wrong — " +
                "with your OB's okay.",
            days = listOf(
                PresetDay(
                    "Day A",
                    listOf(
                        PresetExercise("Bodyweight Squat", 3, 12, isBodyweight = true),
                        PresetExercise("Seated Cable Row", 3, 12, weight = 40.0),
                        PresetExercise("Dumbbell Shoulder Press", 3, 10, weight = 10.0),
                        PresetExercise("Dumbbell Lateral Raise", 2, 15, weight = 5.0),
                        PresetExercise("Standing Calf Raise", 2, 15, isBodyweight = true),
                    ),
                ),
                PresetDay(
                    "Day B",
                    listOf(
                        PresetExercise("Goblet Squat", 3, 10, weight = 15.0),
                        PresetExercise("Lat Pulldown", 3, 12, weight = 40.0),
                        PresetExercise("Dumbbell Incline Press", 3, 10, weight = 10.0),
                        PresetExercise("Face Pull", 3, 15, weight = 20.0),
                        PresetExercise("Cable Glute Kickback", 2, 12, weight = 10.0),
                    ),
                ),
            ),
        ),
        PresetProgram(
            id = "postpartum_rebuild",
            displayName = "Postpartum Rebuild",
            description = "A gentle return to training after giving birth — core- and " +
                "pelvic-floor-friendly, no crunches and no heavy lifting. Start only " +
                "after your doctor clears exercise (often around 6 weeks), and progress " +
                "by how you feel.",
            days = listOf(
                PresetDay(
                    "Foundations",
                    listOf(
                        PresetExercise("Glute Bridge", 3, 12, isBodyweight = true),
                        PresetExercise("Bodyweight Squat", 3, 10, isBodyweight = true),
                        PresetExercise("Step-Up", 2, 10, isBodyweight = true),
                        PresetExercise("Plank", 3, 1, isBodyweight = true),
                        PresetExercise("Standing Calf Raise", 2, 15, isBodyweight = true),
                    ),
                ),
                PresetDay(
                    "Light Strength",
                    listOf(
                        PresetExercise("Goblet Squat", 3, 10, weight = 15.0),
                        PresetExercise("Seated Cable Row", 3, 12, weight = 30.0),
                        PresetExercise("Dumbbell Shoulder Press", 3, 10, weight = 10.0),
                        PresetExercise("Dumbbell Row", 2, 10, weight = 15.0),
                        PresetExercise("Cable Glute Kickback", 2, 12, weight = 10.0),
                    ),
                ),
            ),
        ),
        PresetProgram(
            id = "back_friendly",
            displayName = "Lower-Back Friendly",
            description = "Strength work that keeps the spine happy: supported rows, " +
                "machines, and glute work instead of heavy hinging off the floor. " +
                "Pain-free range only — clear it with your doctor or physio first.",
            days = listOf(
                PresetDay(
                    "Lower (spine-sparing)",
                    listOf(
                        PresetExercise("Leg Press", 3, 12, weight = 135.0),
                        PresetExercise("Seated Leg Curl", 3, 12, weight = 60.0),
                        PresetExercise("Leg Extension", 3, 12, weight = 50.0),
                        PresetExercise("Glute Bridge", 3, 12, isBodyweight = true),
                        PresetExercise("Standing Calf Raise", 3, 15, isBodyweight = true),
                    ),
                ),
                PresetDay(
                    "Upper (supported)",
                    listOf(
                        PresetExercise("Chest-Supported Row", 3, 10, weight = 25.0),
                        PresetExercise("Dumbbell Bench Press", 3, 10, weight = 30.0),
                        PresetExercise("Lat Pulldown", 3, 10, weight = 90.0),
                        PresetExercise("Dumbbell Shoulder Press", 3, 10, weight = 25.0),
                        PresetExercise("Face Pull", 3, 15, weight = 30.0),
                    ),
                ),
            ),
        ),
        PresetProgram(
            id = "bodyweight_basics",
            displayName = "Bodyweight Basics",
            description = "No equipment needed. Full-body calisthenics you can do " +
                "anywhere — progress by adding reps.",
            days = listOf(
                PresetDay(
                    "Full Body",
                    listOf(
                        PresetExercise("Push-Up", 4, 15, isBodyweight = true),
                        PresetExercise("Pull-Up", 4, 8, isBodyweight = true),
                        PresetExercise("Bodyweight Squat", 4, 20, isBodyweight = true),
                        PresetExercise("Dip", 3, 12, isBodyweight = true),
                        PresetExercise("Lunge", 3, 12, isBodyweight = true),
                        PresetExercise("Glute Bridge", 3, 15, isBodyweight = true),
                        PresetExercise("Plank", 3, 1, isBodyweight = true),
                        PresetExercise("Hollow Hold", 3, 1, isBodyweight = true),
                        PresetExercise("Mountain Climber", 3, 20, isBodyweight = true),
                    ),
                ),
            ),
        ),
    )
}
