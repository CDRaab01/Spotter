package com.spotter.ui.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Home : Screen("home")
    data object Workout : Screen("workout/{sessionId}") {
        fun createRoute(sessionId: String) = "workout/$sessionId"
    }
    data object WorkoutSummary : Screen("workout_summary/{duration}/{doneSets}/{totalSets}/{volume}/{newPrCount}") {
        fun createRoute(duration: Int, doneSets: Int, totalSets: Int, volume: Int, newPrCount: Int) =
            "workout_summary/$duration/$doneSets/$totalSets/$volume/$newPrCount"
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
    data object SessionHistory : Screen("session_history")
    data object Settings : Screen("settings")
    data object Programs : Screen("programs")
    data object ProgramPresets : Screen("program_presets")
    data object ProgramDetail : Screen("program_detail/{programId}") {
        fun createRoute(programId: String) = "program_detail/$programId"
    }
    data object ExerciseLibrary : Screen("exercise_library")
    data object Onboarding : Screen("onboarding")
    data object ForgotPassword : Screen("forgot_password")
    data object ResetPassword : Screen("reset_password")
}
