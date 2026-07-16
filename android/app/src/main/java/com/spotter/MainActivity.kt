package com.spotter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.spotter.ui.navigation.AppNavGraph
import com.spotter.ui.navigation.Screen
import com.spotter.ui.theme.LocalDistanceUnit
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.util.AppPreferences
import com.spotter.util.DarkModePreference
import com.spotter.util.DeepLinkBus
import com.spotter.util.DistanceUnit
import com.spotter.util.NotificationNav
import com.spotter.util.ShortcutBus
import com.spotter.util.ShortcutNav
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
    @Inject lateinit var deepLinkBus: DeepLinkBus
    @Inject lateinit var shortcutBus: ShortcutBus

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // A static launcher shortcut (long-press the app icon) may have opened us. Hold the target
        // in the app-scoped bus; the nav graph / Home honour it once past the auth gate.
        shortcutBus.set(ShortcutNav.parse(intent))

        // Determine start destination synchronously on startup (one-time read, no disk IO overhead)
        val startDestination = runBlocking {
            val token = tokenStore.accessToken.first()
            when {
                token == null -> Screen.Login.route
                !appPreferences.onboardingDone.first() -> Screen.Onboarding.route
                else -> Screen.Home.route
            }
        }

        // Cold-start: if launched from an in-progress notification, route there once.
        val initialDeepLink = NotificationNav.parse(intent)

        setContent {
            val darkModePref by appPreferences.darkMode.collectAsState(initial = DarkModePreference.SYSTEM)
            val weightUnit by appPreferences.weightUnit.collectAsState(initial = WeightUnit.LBS)
            val distanceUnit by appPreferences.distanceUnit.collectAsState(initial = DistanceUnit.MI)

            val isDark = when (darkModePref) {
                DarkModePreference.DARK -> true
                DarkModePreference.LIGHT -> false
                DarkModePreference.SYSTEM -> isSystemInDarkTheme()
            }

            RequestNotificationPermission()

            SpotterTheme(darkTheme = isDark) {
                CompositionLocalProvider(
                    LocalWeightUnit provides weightUnit,
                    LocalDistanceUnit provides distanceUnit,
                ) {
                    // Paint a themed background behind every screen so screens without their
                    // own Scaffold/Surface (e.g. Login, Register) follow the dark/light theme
                    // and inherit a readable onBackground content color.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        AppNavGraph(
                            startDestination = startDestination,
                            initialDeepLink = initialDeepLink,
                        )
                    }
                }
            }
        }
    }

    /** Warm-start: the foreground service launches us SINGLE_TOP, so taps arrive here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationNav.parse(intent)?.let { deepLinkBus.emit(it) }
        shortcutBus.set(ShortcutNav.parse(intent))
    }
}

@androidx.compose.runtime.Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
