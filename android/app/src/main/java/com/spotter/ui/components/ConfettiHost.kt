package com.spotter.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import com.spotter.ui.theme.SpotterBlue
import com.spotter.ui.theme.SpotterGreen
import com.spotter.ui.theme.SpotterOrange
import com.spotter.ui.theme.SpotterVolt
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit

/**
 * A one-shot confetti burst in the brand colors, sized for a celebration overlay (workout
 * complete, new PR). Drop it on top of the content; it renders nothing until [play] flips true.
 */
@Composable
fun ConfettiHost(
    play: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!play) return
    val brandColors = listOf(
        SpotterOrange.toArgb(),
        SpotterVolt.toArgb(),
        SpotterBlue.toArgb(),
        SpotterGreen.toArgb(),
    )
    KonfettiView(
        modifier = modifier.fillMaxSize(),
        parties = listOf(
            Party(
                speed = 0f,
                maxSpeed = 30f,
                damping = 0.9f,
                spread = 360,
                colors = brandColors,
                position = Position.Relative(0.5, 0.3),
                emitter = Emitter(duration = 200, TimeUnit.MILLISECONDS).max(120),
            ),
        ),
    )
}
