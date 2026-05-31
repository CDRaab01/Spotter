package com.spotter.ui.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object Home : Screen("home")
    data object Workout : Screen("workout/{sessionId}") {
        fun createRoute(sessionId: String) = "workout/$sessionId"
    }
    data object Calendar : Screen("calendar")
    data object Progress : Screen("progress")
    data object AiChat : Screen("ai_chat")
}
