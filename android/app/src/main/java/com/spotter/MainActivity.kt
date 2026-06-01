package com.spotter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.spotter.ui.navigation.AppNavGraph
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalDistanceUnit
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.util.AppPreferences
import com.spotter.util.DarkModePreference
import com.spotter.util.DistanceUnit
import com.spotter.util.TokenStore
import com.spotter.util.WeightUnit
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var tokenStore: TokenStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Determine start destination synchronously on startup (one-time read, no disk IO overhead)
        val startDestination = runBlocking {
            val token = tokenStore.accessToken.first()
            when {
                token == null -> Screen.Login.route
                !appPreferences.onboardingDone.first() -> Screen.Onboarding.route
                else -> Screen.Home.route
            }
        }

        setContent {
            val darkModePref by appPreferences.darkMode.collectAsState(initial = DarkModePreference.SYSTEM)
            val weightUnit by appPreferences.weightUnit.collectAsState(initial = WeightUnit.LBS)
            val distanceUnit by appPreferences.distanceUnit.collectAsState(initial = DistanceUnit.MI)

            val isDark = when (darkModePref) {
                DarkModePreference.DARK -> true
                DarkModePreference.LIGHT -> false
                DarkModePreference.SYSTEM -> isSystemInDarkTheme()
            }

            SpotterTheme(darkTheme = isDark) {
                CompositionLocalProvider(
                    LocalWeightUnit provides weightUnit,
                    LocalDistanceUnit provides distanceUnit,
                ) {
                    AppNavGraph(startDestination = startDestination)
                }
            }
        }
    }
}
