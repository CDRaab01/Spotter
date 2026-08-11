package com.spotter.screenshot

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.spotter.R
import com.spotter.data.local.entity.WorkoutProgramEntity
import com.spotter.data.model.SetLogOut
import com.spotter.data.model.UserOut
import com.spotter.data.model.VersionOut
import com.spotter.ui.settings.RemindersBlock
import com.spotter.ui.settings.SettingsActions
import com.spotter.ui.settings.SettingsContent
import com.spotter.ui.settings.SettingsUiState
import com.spotter.ui.settings.TrainingProfileBlock
import com.spotter.util.UiState
import com.spotter.util.UserProfile
import com.spotter.ui.components.BrandLogo
import design.pulse.ui.components.DataText
import com.spotter.ui.components.EmptyState
import com.spotter.ui.components.HeatBar
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.ProgressRing
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader
import design.pulse.ui.components.StatTile
import com.spotter.ui.navigation.PulseBottomBar
import com.spotter.ui.navigation.Screen
import com.spotter.ui.navigation.ActiveBarUi
import com.spotter.ui.navigation.ActiveSessionBar
import com.spotter.ui.progress.LineChart
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.workout.SetLogRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot

/**
 * JVM screenshot tests (Robolectric native graphics + Roborazzi) — render the PULSE UI to PNGs
 * without a device or KVM. Run with `:app:testDebugUnitTest`; images land in `app/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

    // A small tolerance so sub-pixel AA / font-hinting noise across machines doesn't flag a diff
    // when these are compared on CI.
    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.03f),
    )

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            SpotterTheme(darkTheme = dark) {
                // Mirror MainActivity: a themed Surface sets the correct background + content color.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) { content() }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$name.png", roborazziOptions = roborazziOptions)
    }

    @Test fun home_light() = capture("home_light", dark = false) { HomeScene() }
    @Test fun home_dark() = capture("home_dark", dark = true) { HomeScene() }
    @Test fun summary_light() = capture("summary_light", dark = false) { SummaryScene() }
    @Test fun summary_dark() = capture("summary_dark", dark = true) { SummaryScene() }
    @Test fun workout_light() = capture("workout_light", dark = false) { WorkoutScene() }
    @Test fun workout_dark() = capture("workout_dark", dark = true) { WorkoutScene() }
    @Test fun states_dark() = capture("states_dark", dark = true) { StatesScene() }
    @Test fun progress_light() = capture("progress_light", dark = false) { ProgressScene() }
    @Test fun progress_dark() = capture("progress_dark", dark = true) { ProgressScene() }
    @Test fun login_light() = capture("login_light", dark = false) { LoginScene() }
    @Test fun login_dark() = capture("login_dark", dark = true) { LoginScene() }
    @Test fun onboarding_light() = capture("onboarding_light", dark = false) { OnboardingScene() }
    @Test fun calendar_light() = capture("calendar_light", dark = false) { CalendarScene() }
    @Test fun calendar_dark() = capture("calendar_dark", dark = true) { CalendarScene() }
    @Test fun settings_light() = capture("settings_light", dark = false) { SettingsScene() }
    @Test fun settings_dark() = capture("settings_dark", dark = true) { SettingsScene() }
    @Test fun settings_reminders_light() = capture("settings_reminders_light", dark = false) { SettingsRemindersScene() }
    @Test fun settings_reminders_dark() = capture("settings_reminders_dark", dark = true) { SettingsRemindersScene() }
    @Test fun settings_profile_light() = capture("settings_profile_light", dark = false) { SettingsProfileScene() }
    @Test fun settings_profile_dark() = capture("settings_profile_dark", dark = true) { SettingsProfileScene() }
    @Test fun summary_pr_dark() = capture("summary_pr_dark", dark = true) { SummaryScene(prCount = 2, perfect = false) }
    @Test fun shell_dark() = capture("shell_dark", dark = true) { ShellScene() }
    @Test fun coach_adjustment_dark() = capture("coach_adjustment_dark", dark = true) { CoachAdjustmentScene() }
    @Test fun coach_adjustment_light() = capture("coach_adjustment_light", dark = false) { CoachAdjustmentScene() }
    @Test fun app_icon() = capture("app_icon", dark = false) { IconPreviewScene() }
}

@Composable
internal fun HomeScene() {
    val pulse = SpotterTheme.pulse
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(pulse.heroGradient)
                .padding(20.dp),
        ) {
            Text("Good evening, Casey", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                "Next up: Push Day · Today",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(modifier = Modifier.weight(1f), dense = true, value = "12", label = "day streak", channel = pulse.streak)
            StatTile(
                modifier = Modifier.weight(1f),
                dense = true,
                value = "148",
                label = "active min",
                channel = pulse.effort,
                sparkline = listOf(30f, 0f, 45f, 38f, 0f, 35f, 0f),
            )
            StatTile(modifier = Modifier.weight(1f), dense = true, value = "182 lb", label = "bodyweight")
        }
        SectionHeader("Upcoming")
        PanelCard(Modifier.fillMaxWidth()) {
            Text("TODAY", style = MaterialTheme.typography.labelMedium, color = pulse.effort)
            Text("Push Day", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Bench Press · 4×8×135lb",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            PulseButton(text = "Start", onClick = {}, tonal = true, compact = true)
        }
        SectionHeader("Your programs", channel = pulse.strength)
        PanelCard(Modifier.fillMaxWidth(), channel = pulse.effort) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Push / Pull / Legs", style = MaterialTheme.typography.titleMedium)
                    Text("6 days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "ACTIVE",
                    style = MaterialTheme.typography.labelSmall,
                    color = pulse.effort,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(pulse.effortDim)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
internal fun SummaryScene(prCount: Int = 0, perfect: Boolean = true) {
    val pulse = SpotterTheme.pulse
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 56.dp, bottom = 24.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .background(pulse.recoveryDim, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Check, null, tint = pulse.recovery, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (perfect) "Perfect session" else "Session complete",
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                if (perfect) "Every set logged. That's how it's done." else "Another one in the books.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (prCount > 0) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(pulse.strengthDim)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.EmojiEvents, null, tint = pulse.strength, modifier = Modifier.size(18.dp))
                    Text(
                        if (prCount == 1) "New personal record" else "$prCount new personal records",
                        style = MaterialTheme.typography.labelLarge,
                        color = pulse.strength,
                    )
                }
            }
        }
        DataText("12,480 lb", style = SpotterTheme.dataType.dataXL, color = pulse.effort)
        Text("TOTAL VOLUME", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryStat(Modifier.weight(1f), "47:30", "DURATION")
            SummaryStat(Modifier.weight(1f), "18/18", "SETS DONE")
        }
        Spacer(Modifier.height(16.dp))
        SectionHeader("Muscles trained", Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        PanelCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeatBar("Chest", 5400f, 5400f, valueText = "5,400 lb", channel = pulse.effort)
                HeatBar("Shoulders", 3800f, 5400f, valueText = "3,800 lb", channel = pulse.effort)
                HeatBar("Triceps", 2100f, 5400f, valueText = "2,100 lb", channel = pulse.effort)
            }
        }
        Spacer(Modifier.height(24.dp))
        PulseButton(
            text = "Return to Home",
            gradient = pulse.energyGradient,
            onChannel = pulse.onEnergy,
            onClick = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SummaryStat(modifier: Modifier, value: String, label: String) {
    PanelCard(modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DataText(value, style = SpotterTheme.dataType.dataMedium)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
internal fun WorkoutScene() {
    val pulse = SpotterTheme.pulse
    fun set(n: Int, reps: Int, w: Double?, done: Boolean) = SetLogOut(
        id = "s$n", sessionId = "x", exerciseId = "e", setNumber = n,
        reps = reps, weight = w, completed = done,
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The rest instrument, mid-countdown.
        PanelCard(Modifier.fillMaxWidth(), channel = pulse.recovery) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                ProgressRing(
                    progress = 0.62f,
                    channel = pulse.recovery,
                    strokeWidth = 8.dp,
                    modifier = Modifier.size(150.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DataText("1:24", style = SpotterTheme.dataType.dataLarge, color = pulse.recovery)
                        Text("REST", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                PulseButton(
                    text = "Skip rest",
                    onClick = {},
                    tonal = true,
                    compact = true,
                    channel = pulse.recovery,
                    onChannel = pulse.onRecovery,
                    dimChannel = pulse.recoveryDim,
                )
            }
        }
        PanelCard(Modifier.fillMaxWidth()) {
            Text("Bench Press", style = MaterialTheme.typography.titleMedium)
            DataText(
                "4 × 8 @ 135 lb",
                style = SpotterTheme.dataType.numeral,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Last: 8×135lb · 8×135lb", style = MaterialTheme.typography.bodySmall, color = pulse.strength)
            Text("Suggested: 140 lb — progression", style = MaterialTheme.typography.bodySmall, color = pulse.effort)
            Spacer(Modifier.height(8.dp))
            SetLogRow(set(1, 8, 135.0, true), onCommit = { _, _ -> }, onToggleComplete = { _, _ -> })
            SetLogRow(set(2, 8, 135.0, true), onCommit = { _, _ -> }, onToggleComplete = { _, _ -> })
            SetLogRow(set(3, 7, 135.0, false), onCommit = { _, _ -> }, onToggleComplete = { _, _ -> })
            SetLogRow(set(4, 8, null, false), onCommit = { _, _ -> }, onToggleComplete = { _, _ -> })
        }
        PanelCard(Modifier.fillMaxWidth(), channel = pulse.strength) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EmojiEvents, null, tint = pulse.strength, modifier = Modifier.padding(end = 12.dp))
                Column {
                    Text("New PR — Bench Press", style = MaterialTheme.typography.titleSmall)
                    DataText(
                        "135 lb × 8 · est. 1RM 169 lb",
                        style = SpotterTheme.dataType.numeral,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatesScene() {
    EmptyState(
        icon = Icons.AutoMirrored.Filled.Chat,
        title = "Meet your AI Coach",
        subtitle = "Ask anything about training, form, or your plan — or have it build a workout for you.",
        action = { PulseButton(text = "Chat with AI Coach", onClick = {}) },
    )
}

@Composable
internal fun ProgressScene() {
    val pulse = SpotterTheme.pulse
    // Renders the genuine LineChart component (internal) with sample data.
    val points = listOf(135f, 140f, 138f, 145f, 150f, 148f, 155f, 160f)
    val ranges = listOf("1M", "3M", "6M", "1Y", "All")
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Bench Press · max weight", channel = pulse.strength)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ranges.forEachIndexed { i, label ->
                FilterChip(selected = i == 2, onClick = {}, label = { Text(label) })
            }
        }
        PanelCard(Modifier.fillMaxWidth()) {
            LineChart(
                points = points,
                color = pulse.strength,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
        }
        PanelCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EmojiEvents, null, tint = pulse.strength, modifier = Modifier.padding(end = 12.dp))
                Column {
                    Text("Bench Press", style = MaterialTheme.typography.titleSmall)
                    DataText("160 lb × 5", style = SpotterTheme.dataType.numeralLarge, color = pulse.strength)
                    Text(
                        "Est. 1RM 180 lb · Best volume 4,000 lb",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun LoginScene() {
    var email by remember { mutableStateOf("casey@spotter.app") }
    var password by remember { mutableStateOf("••••••••") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandLogo()
        Spacer(Modifier.height(16.dp))
        Text("Spotter", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Your personal fitness coach",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(40.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(24.dp))
        PulseButton(text = "Sign In", onClick = {}, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {}) { Text("Forgot password?") }
        TextButton(onClick = {}) { Text("Don't have an account? Create one") }
    }
}

@Composable
internal fun OnboardingScene() {
    val pulse = SpotterTheme.pulse
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinearProgressIndicator(
            progress = { 0.4f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = pulse.effort,
            trackColor = pulse.hairline,
        )
        Text("What's your primary goal?", style = MaterialTheme.typography.titleLarge)
        OptionCardPreview("Build muscle", selected = true)
        OptionCardPreview("Lose fat", selected = false)
        OptionCardPreview("Increase strength", selected = false)
        OptionCardPreview("General fitness", selected = false)
        Spacer(Modifier.height(4.dp))
        PulseButton(text = "Continue", onClick = {}, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OptionCardPreview(label: String, selected: Boolean) {
    val pulse = SpotterTheme.pulse
    PanelCard(
        modifier = Modifier.fillMaxWidth(),
        channel = if (selected) pulse.effort else null,
        containerColor = if (selected) pulse.effortDim else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (selected) Icon(Icons.Default.Check, null, tint = pulse.effort)
        }
    }
}

/** The coach's live-workout adjustment card (Apply + future-workouts toggle). */
@Composable
internal fun CoachAdjustmentScene() {
    val pulse = SpotterTheme.pulse
    var applyToRoutine by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // An assistant bubble + the proposal card, as they appear in chat.
        PanelCard(Modifier.fillMaxWidth()) {
            Text(
                "No problem — let's swap to dumbbells so you can keep the same movement with less shoulder strain.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        PanelCard(Modifier.fillMaxWidth(), channel = pulse.effort, contentPadding = 12.dp) {
            Text("Workout adjustment", style = MaterialTheme.typography.titleSmall)
            Text(
                "• Swap Bench Press for DB Bench Press at 40 lb",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Also update future workouts", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Changes your program too",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = applyToRoutine, onCheckedChange = { applyToRoutine = it })
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PulseButton("Apply", onClick = {}, compact = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { Text("Dismiss") }
            }
        }
    }
}

/** The app shell: in-progress session strip stacked over the bottom navigation bar. */
@Composable
internal fun ShellScene() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
        ActiveSessionBar(
            ui = ActiveBarUi.Workout(
                sessionId = "s1",
                doneSets = 5,
                totalSets = 12,
                startedAtMs = null,
            ),
            onResume = {},
        )
        PulseBottomBar(currentRoute = Screen.Home.route, onNavigate = {})
    }
}

/** Composites the real adaptive-icon layers (panel bg + dumbbell fg) in squircle + round masks. */
@Composable
private fun IconPreviewScene() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("App icon", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            IconMask(RoundedCornerShape(45)) // round / circle
            IconMask(RoundedCornerShape(28)) // squircle
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconMask(RoundedCornerShape(28), size = 96.dp)
            IconMask(RoundedCornerShape(28), size = 64.dp)
            IconMask(RoundedCornerShape(28), size = 48.dp)
        }
    }
}

@Composable
private fun IconMask(shape: androidx.compose.ui.graphics.Shape, size: androidx.compose.ui.unit.Dp = 144.dp) {
    Box(Modifier.size(size).clip(shape)) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Spotter app icon",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/**
 * Fixture state for the real Settings screen. Previously this scene hand-built a *lookalike*
 * Settings screen, so the baselines couldn't catch anything that actually shipped — the
 * text-under-the-toggle and clipped-stepper defects both lived in code no screenshot rendered.
 */
internal fun settingsFixture(
    nudgeEnabled: Boolean = false,
    profileFilled: Boolean = true,
): SettingsUiState = SettingsUiState(
    user = UiState.Success(
        UserOut(id = "u1", name = "Casey Raab", email = "casey@spotter.app"),
    ),
    appVersion = "1.1.2 (44601)",
    serverVersion = UiState.Success(
        VersionOut(name = "Spotter API", version = "1.1.2", commit = "44fe4b4", builtAt = ""),
    ),
    nudgeEnabled = nudgeEnabled,
    programs = listOf(
        WorkoutProgramEntity(
            id = "p1", serverId = "p1", name = "Push / Pull / Legs", isActive = true,
        ),
        WorkoutProgramEntity(id = "p2", serverId = "p2", name = "Full Body", isActive = false),
    ),
    profileDraft = if (profileFilled) {
        UserProfile(
            experience = "INTERMEDIATE",
            goal = "MUSCLE",
            equipment = "Barbell, rack, dumbbells to 50lb",
            ageGroup = "35_44",
        )
    } else {
        UserProfile()
    },
)

@Composable
internal fun SettingsScene() {
    SettingsContent(state = settingsFixture(), actions = SettingsActions())
}

/** The Reminders group with the nudges on — the four time rows are the regression this guards. */
@Composable
internal fun SettingsRemindersScene() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RemindersBlock(settingsFixture(nudgeEnabled = true), SettingsActions())
    }
}

/** The training-profile form — guards the chip group that used to break labels mid-word. */
@Composable
internal fun SettingsProfileScene() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TrainingProfileBlock(settingsFixture(), SettingsActions())
    }
}

@Composable
internal fun CalendarScene() {
    val pulse = SpotterTheme.pulse
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("June 2026")
        PanelCard(Modifier.fillMaxWidth()) {
            Text("Wednesday, June 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Push Day", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = pulse.recoveryDim, shape = MaterialTheme.shapes.small) {
                    Text("Done", Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = pulse.recovery)
                }
                DataText("18 sets", style = SpotterTheme.dataType.numeral, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        PanelCard(Modifier.fillMaxWidth()) {
            Text("Friday, June 5", style = MaterialTheme.typography.labelMedium, color = pulse.effort)
            Text("Pull Day", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            PulseButton(text = "Start workout now", onClick = {}, modifier = Modifier.fillMaxWidth())
        }
    }
}
