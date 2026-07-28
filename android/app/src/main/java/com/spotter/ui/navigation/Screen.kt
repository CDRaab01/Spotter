package com.spotter.ui.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Home : Screen("home")
    data object Workout : Screen("workout/{sessionId}") {
        fun createRoute(sessionId: String) = "workout/$sessionId"
    }
    data object WorkoutSummary :
        Screen("workout_summary/{duration}/{doneSets}/{totalSets}/{volume}/{newPrCount}?sessionId={sessionId}") {
        /**
         * [sessionId] is the local (Room) session id of the workout just finished — optional, and
         * only used to request the best-effort post-workout coach debrief.
         */
        fun createRoute(
            duration: Int,
            doneSets: Int,
            totalSets: Int,
            volume: Int,
            newPrCount: Int,
            sessionId: String? = null,
        ): String {
            val base = "workout_summary/$duration/$doneSets/$totalSets/$volume/$newPrCount"
            return if (sessionId.isNullOrBlank()) base else "$base?sessionId=$sessionId"
        }
    }
    data object Calendar : Screen("calendar")
    data object Progress : Screen("progress")
    data object AiChat : Screen("ai_chat?sessionId={sessionId}") {
        /** Pass a sessionId to open chat aware of an in-progress workout. */
        fun createRoute(sessionId: String? = null) =
            if (sessionId != null) "ai_chat?sessionId=$sessionId" else "ai_chat"
    }
    data object CreateRoutine : Screen("create_routine")
    data object RoutineDetail : Screen("routine_detail/{routineId}") {
        fun createRoute(routineId: String) = "routine_detail/$routineId"
    }
    /** "Your week": the weekly recap (numbers + optional coach narrative). */
    data object WeeklyRecap : Screen("weekly_recap")
    data object SessionHistory : Screen("session_history")
    data object SessionDetail : Screen("session_detail/{sessionId}") {
        fun createRoute(sessionId: String) = "session_detail/$sessionId"
    }
    data object Settings : Screen("settings")
    data object Programs : Screen("programs")
    data object ProgramPresets : Screen("program_presets")
    data object ProgramPresetDetail : Screen("program_preset_detail/{presetId}") {
        fun createRoute(presetId: String) = "program_preset_detail/$presetId"
    }
    data object ProgramDetail : Screen("program_detail/{programId}") {
        fun createRoute(programId: String) = "program_detail/$programId"
    }
    data object ExerciseLibrary : Screen("exercise_library")
    data object ExerciseDetail : Screen("exercise_detail/{exerciseId}") {
        fun createRoute(exerciseId: String) = "exercise_detail/$exerciseId"
    }
    data object Cardio : Screen("cardio")
    data object CardioOverview : Screen("cardio_overview/{programId}") {
        fun createRoute(programId: String) = "cardio_overview/$programId"
    }
    data object FreeRunConfig : Screen("free_run_config")
    data object ManualCardio : Screen("manual_cardio")
    data object CardioRun : Screen("cardio_run")
    data object Onboarding : Screen("onboarding")
    data object ForgotPassword : Screen("forgot_password")
    data object ResetPassword : Screen("reset_password")
}
