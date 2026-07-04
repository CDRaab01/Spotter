package com.spotter.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import design.pulse.ui.theme.PulseBlue
import design.pulse.ui.theme.PulseGreen
import design.pulse.ui.theme.PulseMotion
import design.pulse.ui.theme.PulseOrange
import design.pulse.ui.theme.PulseViolet
import com.spotter.ui.theme.SpotterTheme
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import java.util.concurrent.TimeUnit

/**
 * A one-shot celebration burst in the four channel colors — deliberately sparse and slow so it
 * reads as a signal, not a party store. Drop it on top of the content; it renders nothing until
 * [play] flips true.
 */
@Composable
fun ConfettiHost(
    play: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!play) return
    val channelColors = listOf(
        PulseBlue.toArgb(),
        PulseViolet.toArgb(),
        PulseOrange.toArgb(),
        PulseGreen.toArgb(),
    )
    KonfettiView(
        modifier = modifier.fillMaxSize(),
        parties = listOf(
            Party(
                speed = 0f,
                maxSpeed = 18f,
                damping = 0.9f,
                spread = 360,
                colors = channelColors,
                position = Position.Relative(0.5, 0.3),
                emitter = Emitter(duration = 200, TimeUnit.MILLISECONDS).max(40),
            ),
        ),
    )
}

/**
 * A soft expanding ring-glow behind [content] — the quieter celebration for PR badges and the
 * summary checkmark, where full confetti would be too loud.
 */
@Composable
fun CelebrationPulse(
    modifier: Modifier = Modifier,
    channel: Color = SpotterTheme.pulse.recovery,
    content: @Composable () -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "celebration")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = PulseMotion.EaseDecel)),
        label = "celebrationRing",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val radius = (size.minDimension / 2f) * (0.6f + 0.4f * progress)
            drawCircle(channel.copy(alpha = (1f - progress) * 0.35f), radius = radius)
        }
        content()
    }
}
