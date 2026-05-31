package com.spotter.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.spotter.ui.ai.AiChatScreen
import com.spotter.ui.auth.LoginScreen
import com.spotter.ui.auth.RegisterScreen
import com.spotter.ui.calendar.CalendarScreen
import com.spotter.ui.home.HomeScreen
import com.spotter.ui.progress.ProgressScreen
import com.spotter.ui.workout.WorkoutScreen

@Composable
fun AppNavGraph() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Screen.Login.route) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Home.route) { popUpTo(0) { inclusive = true } }
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
            )
        }
        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Screen.Home.route) { popUpTo(0) { inclusive = true } }
                },
                onNavigateToLogin = { navController.popBackStack() },
            )
        }
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.Workout.route) { backStack ->
            val sessionId = backStack.arguments?.getString("sessionId") ?: ""
            WorkoutScreen(sessionId = sessionId, navController = navController)
        }
        composable(Screen.Calendar.route) {
            CalendarScreen(navController = navController)
        }
        composable(Screen.Progress.route) {
            ProgressScreen(navController = navController)
        }
        composable(Screen.AiChat.route) {
            AiChatScreen(navController = navController)
        }
    }
}
