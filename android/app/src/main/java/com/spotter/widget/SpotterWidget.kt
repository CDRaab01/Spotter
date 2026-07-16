package com.spotter.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.spotter.MainActivity
import com.spotter.data.local.WidgetSnapshotStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Hilt can't inject Glance objects; the widget pulls the snapshot store via an EntryPoint. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetSnapshotStore(): WidgetSnapshotStore
}

private fun entryPoint(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

private val widgetJson = Json { ignoreUnknownKeys = true }

class SpotterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SpotterWidget()
}

/**
 * The home-screen "today's workout" glance: the active program's next/today workout (or the live
 * session's set progress) at a glance. Reads the app's last-known snapshot (no network of its own),
 * so it shows the same truth as Home — offline included. Tap to open Spotter.
 */
class SpotterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val raw = entryPoint(context).widgetSnapshotStore().read(WidgetSnapshotStore.TODAY)
        val data = raw?.let { runCatching { widgetJson.decodeFromString<WidgetData>(it) }.getOrNull() }
        provideContent {
            GlanceTheme { WidgetBody(data) }
        }
    }
}

// PULSE-adjacent colors, hardcoded: Glance can't consume the Compose theme objects. Spotter leads
// blue (PulseBlue #4D7CFF); dark OLED ink bg (#0B0D10) and recovery green (#34D399) match SpotterTheme.
private val InkBg = Color(0xFF0B0D10)
private val Blue = Color(0xFF4D7CFF)
private val Green = Color(0xFF34D399)
private val TextPrimary = Color(0xFFE7EAF0)
private val TextDim = Color(0xFF9AA3B2)

@Composable
private fun WidgetBody(data: WidgetData?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(InkBg))
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            "Spotter",
            style = TextStyle(color = ColorProvider(Blue), fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(6.dp))
        if (data == null) {
            Text(
                "Open Spotter to sync",
                style = TextStyle(color = ColorProvider(TextDim), fontSize = 13.sp),
            )
            return@Column
        }
        Text("Today's workout", style = TextStyle(color = ColorProvider(TextDim), fontSize = 12.sp))
        Spacer(GlanceModifier.height(2.dp))
        Text(
            data.workoutName,
            style = TextStyle(
                color = ColorProvider(Blue),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 2,
        )
        Spacer(GlanceModifier.height(6.dp))
        Text(
            data.statusLine,
            style = TextStyle(
                color = ColorProvider(if (data.inProgress) Green else TextPrimary),
                fontSize = 15.sp,
                fontWeight = if (data.inProgress) FontWeight.Medium else FontWeight.Normal,
            ),
        )
    }
}
