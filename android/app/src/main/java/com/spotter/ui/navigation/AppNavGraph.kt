package com.spotter.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.spotter.ui.ai.AiChatScreen
import com.spotter.ui.auth.AuthViewModel
import com.spotter.ui.auth.ForgotPasswordScreen
import com.spotter.ui.auth.LoginScreen
import com.spotter.ui.auth.RegisterScreen
import com.spotter.ui.auth.ResetPasswordScreen
import com.spotter.ui.calendar.CalendarScreen
import com.spotter.ui.exercise.ExerciseLibraryScreen
import com.spotter.ui.history.SessionHistoryScreen
import com.spotter.ui.home.HomeScreen
import com.spotter.ui.onboarding.OnboardingScreen
import com.spotter.ui.plan.CreatePlanScreen
import com.spotter.ui.plan.PlanDetailScreen
import com.spotter.ui.progress.ProgressScreen
import com.spotter.ui.settings.SettingsScreen
import com.spotter.ui.program.ProgramDetailScreen
import com.spotter.ui.program.ProgramScreen
import com.spotter.ui.workout.WorkoutScreen
import com.spotter.ui.workout.WorkoutSummaryScreen
import com.spotter.ui.workout.WorkoutSummaryStore

@Composable
fun AppNavGraph(startDestination: String = Screen.Login.route) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        authViewModel.logoutEvents.collect {
            navController.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } }
        }
    }
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = { onboardingDone ->
                    val dest = if (onboardingDone) Screen.Home.route else Screen.Onboarding.route
                    navController.navigate(dest) { popUpTo(0) { inclusive = true } }
                },
                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                onNavigateToForgotPassword = { navController.navigate(Screen.ForgotPassword.route) },
            )
        }
        composable(Screen.ForgotPassword.route) {
            ForgotPasswordScreen(
                onBack = { navController.popBackStack() },
                onCodeSent = { navController.navigate(Screen.ResetPassword.route) },
            )
        }
        composable(Screen.ResetPassword.route) {
            ResetPasswordScreen(
                onBack = { navController.popBackStack() },
                onResetSuccess = {
                    navController.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Screen.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Screen.Onboarding.route) { popUpTo(0) { inclusive = true } }
                },
                onNavigateToLogin = { navController.popBackStack() },
            )
        }
        composable(Screen.Onboarding.route) {
            OnboardingScreen(navController = navController)
        }
        composable(Screen.Home.route) {
            HomeScreen(navController = navController)
        }
        composable(Screen.Workout.route) { backStack ->
            val sessionId = backStack.arguments?.getString("sessionId") ?: ""
            WorkoutScreen(sessionId = sessionId, navController = navController)
        }
        composable(
            route = Screen.WorkoutSummary.route,
            arguments = listOf(
                navArgument("duration") { type = NavType.IntType },
                navArgument("doneSets") { type = NavType.IntType },
                navArgument("totalSets") { type = NavType.IntType },
                navArgument("volume") { type = NavType.IntType },
            ),
        ) { backStack ->
            val duration = backStack.arguments?.getInt("duration") ?: 0
            val doneSets = backStack.arguments?.getInt("doneSets") ?: 0
            val totalSets = backStack.arguments?.getInt("totalSets") ?: 0
            val volume = backStack.arguments?.getInt("volume") ?: 0
            WorkoutSummaryScreen(
                durationSeconds = duration,
                doneSets = doneSets,
                totalSets = totalSets,
                totalVolumeLb = volume,
                muscleGroups = WorkoutSummaryStore.muscleGroups,
                navController = navController,
            )
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
        composable(Screen.CreatePlan.route) {
            CreatePlanScreen(navController = navController)
        }
        composable(
            route = Screen.PlanDetail.route,
            arguments = listOf(navArgument("planId") { type = NavType.StringType }),
        ) { backStack ->
            val planId = backStack.arguments?.getString("planId") ?: ""
            PlanDetailScreen(planId = planId, navController = navController)
        }
        composable(Screen.SessionHistory.route) {
            SessionHistoryScreen(navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable(Screen.Programs.route) {
            ProgramScreen(navController = navController)
        }
        composable(
            route = Screen.ProgramDetail.route,
            arguments = listOf(navArgument("programId") { type = NavType.StringType }),
        ) { backStack ->
            val programId = backStack.arguments?.getString("programId") ?: ""
            ProgramDetailScreen(programId = programId, navController = navController)
        }
        composable(Screen.ExerciseLibrary.route) {
            ExerciseLibraryScreen(navController = navController)
        }
    }
}
