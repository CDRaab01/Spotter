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
) {
    /**
     * A day with no exercises is a rest day — the same shape a program day takes on the server
     * (a labelled day with no routine linked), so it survives the accept-program round trip.
     */
    val isRest: Boolean get() = exercises.isEmpty()
}

/** A rest day in a preset's cycle. Prescribed cadence is only real if the rests are in the list. */
fun restDay(label: String = "Rest"): PresetDay = PresetDay(label, emptyList())

data class PresetProgram(
    val id: String,
    val displayName: String,
    val description: String,
    /**
     * The repeating cycle, rest days included: the program advances one day per calendar day,
     * so `days.size` is the cycle length and the training days inside it set the real frequency.
     */
    val days: List<PresetDay>,
) {
    val trainingDays: List<PresetDay> get() = days.filter { !it.isRest }
}

/**
 * One line describing what the preset's cycle actually prescribes, e.g.
 * "2 workouts in a 4-day cycle · about 3.5 a week". Derived from the days list rather than the
 * prose, so the displayed cadence can never drift from what gets scheduled.
 */
fun presetCadenceLine(preset: PresetProgram): String {
    val training = preset.trainingDays.size
    val cycle = preset.days.size.coerceAtLeast(1)
    val perWeek = training * 7.0 / cycle
    val rounded = if (perWeek % 1.0 == 0.0) perWeek.toInt().toString()
                  else String.format(java.util.Locale.US, "%.1f", perWeek)
    val workouts = if (training == 1) "1 workout" else "$training workouts"
    return "$workouts in a $cycle-day cycle · about $rounded a week"
}

object ProgramPresets {

    fun byId(id: String): PresetProgram? = all.firstOrNull { it.id == id }

    val all: List<PresetProgram> = listOf(
        PresetProgram(
            id = "stronglifts_5x5",
            displayName = "StrongLifts 5×5",
            description = "Classic barbell strength. Alternate Day A / Day B with a rest day " +
                "between each — about 3–4 sessions a week. Add weight every session.",
            days = listOf(
                PresetDay(
                    "Day A",
                    listOf(
                        PresetExercise("Barbell Back Squat", 5, 5, weight = 95.0),
                        PresetExercise("Bench Press", 5, 5, weight = 95.0),
                        PresetExercise("Barbell Row", 5, 5, weight = 95.0),
                    ),
                ),
                restDay(),
                PresetDay(
                    "Day B",
                    listOf(
                        PresetExercise("Barbell Back Squat", 5, 5, weight = 95.0),
                        PresetExercise("Overhead Press", 5, 5, weight = 65.0),
                        PresetExercise("Conventional Deadlift", 1, 5, weight = 135.0),
                    ),
                ),
                restDay(),
            ),
        ),
        PresetProgram(
            id = "ppl",
            displayName = "Push / Pull / Legs",
            description = "A hypertrophy split hitting each muscle group with compound-first " +
                "volume: three sessions, then a rest day, repeat.",
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
                restDay(),
            ),
        ),
        PresetProgram(
            id = "upper_lower",
            displayName = "Upper / Lower",
            description = "Alternating upper- and lower-body days: two on, one off — " +
                "4–5 sessions a week. Balanced strength and size.",
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
                restDay(),
            ),
        ),
        PresetProgram(
            id = "full_body",
            displayName = "Full Body (Beginner)",
            description = "One balanced full-body session every other day — about 3 a week. " +
                "The simplest way to build a base of strength.",
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
                restDay(),
            ),
        ),
        PresetProgram(
            id = "dumbbell_only",
            displayName = "Dumbbell Only",
            description = "A full-body home routine needing only dumbbells, alternating two " +
                "days with rest between. Great for training without a barbell or machines.",
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
                restDay(),
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
                restDay(),
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
                "extensions. Three sessions a week with a rest day between each. Stay in " +
                "pain-free range, and get cleared by your doctor or physio first.",
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
                restDay(),
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
                restDay(),
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
                restDay(),
            ),
        ),
        PresetProgram(
            id = "prenatal_late_term",
            displayName = "Prenatal — Third Trimester",
            description = "Light full-body strength for late pregnancy: seated or " +
                "standing work only, nothing lying flat on your back, no breath-holding " +
                "or core flexion. Two easy sessions a week with plenty of rest. Keep loads " +
                "light and stop anything that feels wrong — with your OB's okay.",
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
                restDay(),
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
                restDay(),
                restDay(),
            ),
        ),
        // Postpartum comes in two stages rather than one flat program: coming back is
        // progressive, and a single "rebuild" block either starts too hard or stays too easy.
        // Stage 1 deliberately contains no crunches, twists, or long anti-extension holds
        // (planks, hollow holds) — those load the abdominal wall and pelvic floor hardest, which
        // is exactly what this stage is not for. They belong to a later stage, symptom-free.
        PresetProgram(
            id = "postpartum_rebuild",
            displayName = "Postpartum — First Weeks Back",
            description = "Stage 1 of coming back after giving birth: short, gentle, mostly " +
                "bodyweight sessions built around hips, glutes, and supported pulling (which " +
                "is what carrying and feeding a baby actually demands). No crunches, twists, " +
                "planks, or heavy lifting. Two sessions a week, about 20 minutes each — a " +
                "short session done beats a long one skipped.\n\nStart only once your doctor " +
                "or midwife has cleared you to exercise; that timing is theirs to give, not " +
                "this app's. If you notice leaking, heaviness or bulging in the pelvis, your " +
                "abdomen doming along the midline, pain (including around a C-section scar), " +
                "or bleeding that restarts, stop and speak to your doctor or a pelvic floor " +
                "physiotherapist — that referral exists and is worth asking for. Move up to " +
                "\"Postpartum — Rebuilding Strength\" when this feels genuinely easy.",
            days = listOf(
                PresetDay(
                    "Foundations",
                    listOf(
                        PresetExercise("Glute Bridge", 3, 12, isBodyweight = true),
                        PresetExercise("Bodyweight Squat", 3, 10, isBodyweight = true),
                        PresetExercise("Seated Cable Row", 3, 12, weight = 25.0),
                        PresetExercise("Standing Calf Raise", 2, 15, isBodyweight = true),
                    ),
                ),
                restDay(),
                restDay(),
                PresetDay(
                    "Gentle Full Body",
                    listOf(
                        PresetExercise("Step-Up", 3, 10, isBodyweight = true),
                        PresetExercise("Hip Thrust", 3, 12, isBodyweight = true),
                        PresetExercise("Lat Pulldown", 3, 12, weight = 40.0),
                        PresetExercise("Dumbbell Row", 2, 10, weight = 10.0),
                    ),
                ),
                restDay(),
                restDay(),
                restDay(),
            ),
        ),
        PresetProgram(
            id = "postpartum_strength",
            displayName = "Postpartum — Rebuilding Strength",
            description = "Stage 2, for when the first weeks back feel easy and you have no " +
                "symptoms: light loaded strength three days a week, still supported and still " +
                "free of crunches, twists, and max-effort lifting. Hips and glutes lead, " +
                "pulling outweighs pressing, and the hinge comes back light.\n\nKeep " +
                "progressing by how your body responds rather than by the calendar, and hold " +
                "here as long as you like. If leaking, pelvic heaviness or bulging, abdominal " +
                "doming, pain, or renewed bleeding show up, stop and check in with your " +
                "doctor or a pelvic floor physiotherapist before adding more load.",
            days = listOf(
                PresetDay(
                    "Lower + Glutes",
                    listOf(
                        PresetExercise("Goblet Squat", 3, 10, weight = 20.0),
                        PresetExercise("Hip Thrust", 3, 12, weight = 45.0),
                        PresetExercise("Dumbbell Reverse Lunge", 2, 10, weight = 10.0),
                        PresetExercise("Standing Calf Raise", 2, 15, isBodyweight = true),
                    ),
                ),
                restDay(),
                PresetDay(
                    "Upper (supported)",
                    listOf(
                        PresetExercise("Chest-Supported Row", 3, 10, weight = 25.0),
                        PresetExercise("Dumbbell Bench Press", 3, 10, weight = 15.0),
                        PresetExercise("Lat Pulldown", 3, 12, weight = 50.0),
                        PresetExercise("Face Pull", 3, 15, weight = 25.0),
                    ),
                ),
                restDay(),
                PresetDay(
                    "Full Body",
                    listOf(
                        PresetExercise("Dumbbell Romanian Deadlift", 3, 10, weight = 20.0),
                        PresetExercise("Seated Cable Row", 3, 12, weight = 35.0),
                        PresetExercise("Dumbbell Shoulder Press", 2, 10, weight = 10.0),
                        PresetExercise("Glute Bridge", 2, 15, isBodyweight = true),
                    ),
                ),
                restDay(),
                restDay(),
            ),
        ),
        PresetProgram(
            id = "back_friendly",
            displayName = "Lower-Back Friendly",
            description = "Strength work that keeps the spine happy: supported rows, " +
                "machines, and glute work instead of heavy hinging off the floor, with a " +
                "rest day between sessions. Pain-free range only — clear it with your " +
                "doctor or physio first.",
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
                restDay(),
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
                restDay(),
            ),
        ),
        PresetProgram(
            id = "bodyweight_basics",
            displayName = "Bodyweight Basics",
            description = "No equipment needed. Full-body calisthenics you can do " +
                "anywhere, every other day — progress by adding reps.",
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
                restDay(),
            ),
        ),
    )
}
