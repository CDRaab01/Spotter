package com.spotter.screenshot

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.spotter.ui.theme.SpotterTheme
import style.sift.compose.DesignSlopSuite
import style.sift.compose.TokenScan
import style.sift.core.config.SiftConfig
import java.io.File

/**
 * Sift design-slop audit for Spotter (CLAUDE.md §7 of the Sift repo). Reuses the exact scene
 * helpers the Roborazzi [ScreenshotTest] renders — same PULSE components, same sample data — so the
 * audit and the screenshots never drift. The inherited `audit()` renders each scene on Robolectric
 * NATIVE graphics, runs the rule catalog, writes `build/sift/report.json`, and fails on any
 * error-severity finding (low-contrast-text / tiny-touch-target).
 *
 * Tokens are scanned from Spotter's theme package so the palette/font rules see the real design
 * intent. Config is read from `.sift/config.json` (module-relative), falling back to defaults.
 */
class SpotterDesignSlopTest : DesignSlopSuite(
    config = SiftConfig.fromFileOrDefault(),
    tokens = TokenScan.scan(listOf(File("src/main/java/com/spotter/ui/theme"))),
) {
    init {
        register("home") { HomeScene() }
        register("summary") { SummaryScene(prCount = 2, perfect = true) }
        register("workout") { WorkoutScene() }
        register("states") { StatesScene() }
        register("progress") { ProgressScene() }
        register("login") { LoginScene() }
        register("onboarding") { OnboardingScene() }
        register("coach_adjustment") { CoachAdjustmentScene() }
        register("settings") { SettingsScene() }
        register("calendar") { CalendarScene() }
        register("shell") { ShellScene() }
    }

    /** Register a scene in both dark and light, themed + backed by a themed Surface like MainActivity. */
    private fun register(name: String, content: @Composable () -> Unit) {
        scene(name, dark = true) { Themed(dark = true, content) }
        scene(name, dark = false) { Themed(dark = false, content) }
    }
}

@Composable
private fun Themed(dark: Boolean, content: @Composable () -> Unit) {
    SpotterTheme(darkTheme = dark) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}
