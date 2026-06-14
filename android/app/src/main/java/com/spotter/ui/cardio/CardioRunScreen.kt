package com.spotter.ui.cardio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.spotter.data.model.CardioPhase
import com.spotter.ui.components.DataText
import com.spotter.ui.components.ProgressRing
import com.spotter.ui.components.PulseButton
import com.spotter.ui.theme.SpotterTheme

@Composable
fun CardioRunScreen(
    navController: NavController,
    viewModel: CardioRunViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val pulse = SpotterTheme.pulse
    var locked by remember { mutableStateOf(false) }

    KeepScreenOn()

    // Back leaves the run in progress (resumable) — unless locked, which swallows it.
    BackHandler(enabled = true) {
        if (locked) return@BackHandler
        viewModel.pauseAndExit()
        navController.popBackStack()
    }

    val run = state
    if (run == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No active run", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(SpotterTheme.spacing.lg),
    ) {
        // Top bar: lock (left), elapsed/total + week·day (right).
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { locked = !locked }) {
                Icon(
                    imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (locked) "Unlock controls" else "Lock controls",
                    tint = if (locked) pulse.recovery else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                DataText(
                    text = if (run.isOpenEnded) {
                        CardioFormat.clock(run.totalElapsedSec)
                    } else {
                        "${CardioFormat.clock(run.totalElapsedSec)} / ${CardioFormat.clock(run.totalDurationSec)}"
                    },
                    style = SpotterTheme.dataType.numeralLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                run.weekDayLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Center: phase label + big countdown / count-up.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 40.dp, bottom = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (run.isComplete) {
                CompleteContent(run.totalElapsedSec)
            } else {
                RunningContent(run, pulse)
            }
        }

        // Bottom controls.
        Box(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
        ) {
            if (run.isComplete) {
                PulseButton(
                    text = "Return to overview",
                    onClick = {
                        viewModel.clear()
                        navController.popBackStack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    channel = pulse.recovery,
                    onChannel = pulse.onRecovery,
                    gradient = androidx.compose.ui.graphics.SolidColor(pulse.recovery),
                )
            } else {
                Controls(
                    paused = run.isPaused,
                    showSkipWarmup = run.isWarmup && !run.isOpenEnded,
                    enabled = !locked,
                    onPause = { viewModel.pause() },
                    onResume = { viewModel.resume() },
                    onSkipWarmup = { viewModel.skipWarmup() },
                    onFinish = {
                        viewModel.finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun RunningContent(run: CardioRunState, pulse: com.spotter.ui.theme.PulseColors) {
    Text(
        text = run.phase.label.uppercase(),
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.size(SpotterTheme.spacing.lg))
    if (run.isOpenEnded) {
        DataText(
            text = CardioFormat.clock(run.totalElapsedSec),
            style = SpotterTheme.dataType.dataXL,
            color = pulse.recovery,
        )
    } else {
        val progress = if (run.intervalDurationSec > 0) {
            run.intervalElapsedSec.toFloat() / run.intervalDurationSec.toFloat()
        } else 0f
        ProgressRing(
            progress = progress,
            modifier = Modifier.size(240.dp),
            channel = pulse.recovery,
            strokeWidth = 10.dp,
        ) {
            DataText(
                text = CardioFormat.clock(run.intervalRemainingSec),
                style = SpotterTheme.dataType.dataLarge,
                color = pulse.recovery,
            )
        }
    }
    Spacer(Modifier.size(SpotterTheme.spacing.xl))
    if (!run.isOpenEnded) {
        IntervalBar(
            intervals = run.intervals,
            currentIndex = run.currentIndex,
            modifier = Modifier.padding(horizontal = SpotterTheme.spacing.sm),
        )
    }
    if (run.isPaused) {
        Spacer(Modifier.size(SpotterTheme.spacing.md))
        Text(
            "Paused",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompleteContent(totalElapsedSec: Int) {
    val pulse = SpotterTheme.pulse
    Text(
        text = "COMPLETE",
        style = MaterialTheme.typography.displaySmall,
        fontWeight = FontWeight.Bold,
        color = pulse.recovery,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.size(SpotterTheme.spacing.lg))
    DataText(
        text = CardioFormat.clock(totalElapsedSec),
        style = SpotterTheme.dataType.dataXL,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.size(SpotterTheme.spacing.sm))
    Text(
        "Nice work — that's in the books.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Controls(
    paused: Boolean,
    showSkipWarmup: Boolean,
    enabled: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkipWarmup: () -> Unit,
    onFinish: () -> Unit,
) {
    val pulse = SpotterTheme.pulse
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
    ) {
        if (paused) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpotterTheme.spacing.sm),
            ) {
                PulseButton(
                    text = "Resume",
                    onClick = onResume,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    channel = pulse.recovery,
                    onChannel = pulse.onRecovery,
                    gradient = androidx.compose.ui.graphics.SolidColor(pulse.recovery),
                )
                PulseButton(
                    text = "Finish",
                    onClick = onFinish,
                    enabled = enabled,
                    tonal = true,
                    modifier = Modifier.weight(1f),
                    channel = pulse.recovery,
                    dimChannel = pulse.recoveryDim,
                )
            }
        } else {
            if (showSkipWarmup) {
                PulseButton(
                    text = "Skip warm-up",
                    onClick = onSkipWarmup,
                    enabled = enabled,
                    tonal = true,
                    modifier = Modifier.fillMaxWidth(),
                    channel = pulse.recovery,
                    dimChannel = pulse.recoveryDim,
                )
            }
            // Neutral gray Pause pill.
            PulseButton(
                text = "Pause",
                onClick = onPause,
                enabled = enabled,
                tonal = true,
                modifier = Modifier.fillMaxWidth(),
                channel = MaterialTheme.colorScheme.onSurface,
                dimChannel = SpotterTheme.pulse.panelHigh,
            )
        }
    }
}

/** Keep the screen awake while the run screen is visible. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
