package com.spotter.ui.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Home : Screen("home")
    data object Workout : Screen("workout/{sessionId}") {
        fun createRoute(sessionId: String) = "workout/$sessionId"
    }
    data object WorkoutSummary : Screen("workout_summary/{duration}/{doneSets}/{totalSets}/{volume}") {
        fun createRoute(duration: Int, doneSets: Int, totalSets: Int, volume: Int) =
            "workout_summary/$duration/$doneSets/$totalSets/$volume"
    }
    data object Calendar : Screen("calendar")
    data object Progress : Screen("progress")
    data object AiChat : Screen("ai_chat")
    data object CreatePlan : Screen("create_plan")
    data object PlanDetail : Screen("plan_detail/{planId}") {
        fun createRoute(planId: String) = "plan_detail/$planId"
    }
    data object SessionHistory : Screen("session_history")
    data object Settings : Screen("settings")
    data object Programs : Screen("programs")
    data object Onboarding : Screen("onboarding")
    data object ForgotPassword : Screen("forgot_password")
    data object ResetPassword : Screen("reset_password")
}
