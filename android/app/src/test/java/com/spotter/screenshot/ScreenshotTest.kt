package com.spotter.screenshot

import android.app.Application
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import com.spotter.data.model.SetLogOut
import com.spotter.ui.components.AnimatedCounter
import com.spotter.ui.components.BrandLogo
import com.spotter.ui.components.EmptyState
import com.spotter.ui.components.GradientButton
import com.spotter.ui.components.SectionHeader
import com.spotter.ui.components.SpotterCard
import com.spotter.ui.components.StatTile
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
 * JVM screenshot tests (Robolectric native graphics + Roborazzi) — render the redesigned UI to
 * PNGs without a device or KVM. Run with `:app:testDebugUnitTest`; images land in
 * `app/screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
class ScreenshotTest {

    @get:Rule val compose = createComposeRule()

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
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    @Test fun home_light() = capture("home_light", dark = false) { HomeScene() }
    @Test fun home_dark() = capture("home_dark", dark = true) { HomeScene() }
    @Test fun summary_light() = capture("summary_light", dark = false) { SummaryScene() }
    @Test fun summary_dark() = capture("summary_dark", dark = true) { SummaryScene() }
    @Test fun workout_light() = capture("workout_light", dark = false) { WorkoutScene() }
    @Test fun states_dark() = capture("states_dark", dark = true) { StatesScene() }
}

@Composable
private fun HomeScene() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Gradient greeting hero
        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(SpotterTheme.brand.heroGradient)
                .padding(20.dp),
        ) {
            Column {
                Text(
                    "LET'S TRAIN",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
                Text("Good evening, Casey", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
        }
        // Stat band
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpotterCard(Modifier.weight(1f), contentPadding = 14.dp) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AnimatedCounter(target = 12, style = MaterialTheme.typography.headlineMedium)
                        Text(" 🔥", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        "day streak",
                        style = MaterialTheme.typography.labelMedium,
                        color = SpotterTheme.brand.streak,
                    )
                }
            }
            StatTile(modifier = Modifier.weight(1f), animatedValue = 4, label = "this week")
            StatTile(modifier = Modifier.weight(1f), value = "182 lb", label = "bodyweight")
        }
        SectionHeader("Upcoming workouts")
        SpotterCard(Modifier.fillMaxWidth()) {
            Text("Wed · Push Day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Bench Press · Overhead Press · Dips", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            GradientButton(text = "Start", onClick = {}, modifier = Modifier.fillMaxWidth())
        }
        SectionHeader("Your plans")
        SpotterCard(Modifier.fillMaxWidth()) {
            Text("Upper / Lower Split", style = MaterialTheme.typography.titleMedium)
            Text("ai", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SummaryScene() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(SpotterTheme.brand.heroGradient)
                .padding(top = 56.dp, bottom = 40.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .background(Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(60.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text("Perfect session!", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Text(
                "Every set logged. That's how it's done.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryStat(Modifier.weight(1f), "47:30", "Duration")
            SummaryStat(Modifier.weight(1f), "18 / 18", "Sets done")
        }
        Spacer(Modifier.height(12.dp))
        SpotterCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("12,480 lb", style = MaterialTheme.typography.displaySmall)
                Text(
                    "total volume lifted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        GradientButton(
            text = "Return to Home",
            onClick = {},
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun SummaryStat(modifier: Modifier, value: String, label: String) {
    SpotterCard(modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun WorkoutScene() {
    fun set(n: Int, reps: Int, w: Double?, done: Boolean) = SetLogOut(
        id = "s$n", sessionId = "x", exerciseId = "e", setNumber = n,
        reps = reps, weight = w, completed = done,
    )
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SpotterCard(Modifier.fillMaxWidth()) {
            Text("Bench Press", style = MaterialTheme.typography.titleMedium)
            Text(
                "4 × 8 @ 135 lb",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            SetLogRow(set(1, 8, 135.0, true), onCommit = { _, _ -> }, onToggleComplete = { _, _ -> })
            SetLogRow(set(2, 8, 135.0, true), onCommit = { _, _ -> }, onToggleComplete = { _, _ -> })
            SetLogRow(set(3, 7, 135.0, false), onCommit = { _, _ -> }, onToggleComplete = { _, _ -> })
            SetLogRow(set(4, 8, null, false), onCommit = { _, _ -> }, onToggleComplete = { _, _ -> })
        }
        SpotterCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EmojiEvents, null, tint = SpotterTheme.brand.streak, modifier = Modifier.padding(end = 12.dp))
                Column {
                    Text("New PR — Bench Press", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Top: 135 lb × 8 · Est. 1RM 169 lb",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatesScene() {
    EmptyState(
        icon = Icons.Default.Chat,
        title = "Meet your AI Coach",
        subtitle = "Ask anything about training, form, or your plan — or have it build a workout for you.",
        action = { GradientButton(text = "Chat with AI Coach", onClick = {}) },
    )
}
