package com.spotter.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
import com.spotter.ui.plan.CreateRoutineScreen
import com.spotter.ui.plan.RoutineDetailScreen
import com.spotter.ui.progress.ProgressScreen
import com.spotter.ui.settings.SettingsScreen
import com.spotter.ui.program.ProgramDetailScreen
import com.spotter.ui.program.ProgramPresetsScreen
import com.spotter.ui.program.ProgramScreen
import com.spotter.ui.workout.WorkoutScreen
import com.spotter.ui.workout.WorkoutSummaryScreen
import com.spotter.ui.workout.WorkoutSummaryStore

/** Routes where the shell chrome (bottom bar + resume strip) must stay out of the way. */
private val noChromeRoutes = setOf(
    Screen.Login.route,
    Screen.Register.route,
    Screen.ForgotPassword.route,
    Screen.ResetPassword.route,
    Screen.Onboarding.route,
    Screen.Workout.route,
    Screen.WorkoutSummary.route,
)

private val bottomBarRoutes = TopLevelDestination.entries.map { it.route }.toSet()

@Composable
fun AppNavGraph(startDestination: String = Screen.Login.route) {
    val navController = rememberNavController()
    val authViewModel: AuthViewModel = hiltViewModel()
    val shellViewModel: AppShellViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        authViewModel.logoutEvents.collect {
            navController.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true } }
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in bottomBarRoutes
    val activeSession by shellViewModel.activeSession.collectAsState()
    val showResumeBar = activeSession != null && currentRoute != null && currentRoute !in noChromeRoutes

    Scaffold(
        bottomBar = {
            Column {
                if (showResumeBar) {
                    WorkoutResumeBar(
                        onResume = {
                            activeSession?.let {
                                navController.navigate(Screen.Workout.createRoute(it.id))
                            }
                        },
                    )
                }
                if (showBottomBar) {
                    PulseBottomBar(
                        currentRoute = currentRoute,
                        onNavigate = { dest ->
                            navController.navigate(dest.navRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
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
                    navArgument("newPrCount") { type = NavType.IntType },
                ),
            ) { backStack ->
                val duration = backStack.arguments?.getInt("duration") ?: 0
                val doneSets = backStack.arguments?.getInt("doneSets") ?: 0
                val totalSets = backStack.arguments?.getInt("totalSets") ?: 0
                val volume = backStack.arguments?.getInt("volume") ?: 0
                val newPrCount = backStack.arguments?.getInt("newPrCount") ?: 0
                WorkoutSummaryScreen(
                    durationSeconds = duration,
                    doneSets = doneSets,
                    totalSets = totalSets,
                    totalVolumeLb = volume,
                    muscleGroups = WorkoutSummaryStore.muscleGroups,
                    newPrCount = newPrCount,
                    navController = navController,
                )
            }
            composable(Screen.Calendar.route) {
                CalendarScreen(navController = navController)
            }
            composable(Screen.Progress.route) {
                ProgressScreen(navController = navController)
            }
            composable(
                route = Screen.AiChat.route,
                arguments = listOf(
                    navArgument("sessionId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                AiChatScreen(navController = navController)
            }
            composable(Screen.CreateRoutine.route) {
                CreateRoutineScreen(navController = navController)
            }
            composable(
                route = Screen.RoutineDetail.route,
                arguments = listOf(navArgument("routineId") { type = NavType.StringType }),
            ) { backStack ->
                val routineId = backStack.arguments?.getString("routineId") ?: ""
                RoutineDetailScreen(routineId = routineId, navController = navController)
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
            composable(Screen.ProgramPresets.route) {
                ProgramPresetsScreen(navController = navController)
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
}
