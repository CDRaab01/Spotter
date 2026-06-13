package com.spotter.ui.navigation

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.spotter.ui.theme.SpotterTheme

/** The app's bottom navigation: a flat panel with a hairline top rule, selection in effort cyan. */
@Composable
fun PulseBottomBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = SpotterTheme.pulse
    Column(modifier) {
        HorizontalDivider(thickness = 1.dp, color = pulse.hairline)
        NavigationBar(
            containerColor = pulse.panel,
            tonalElevation = 0.dp,
        ) {
            TopLevelDestination.entries.forEach { dest ->
                val selected = currentRoute == dest.route
                NavigationBarItem(
                    selected = selected,
                    onClick = { onNavigate(dest) },
                    icon = {
                        Icon(
                            imageVector = if (selected) dest.icon else dest.iconOutlined,
                            contentDescription = dest.label,
                        )
                    },
                    label = { Text(dest.label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = pulse.effort,
                        selectedTextColor = pulse.effort,
                        indicatorColor = pulse.effortDim,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}

/**
 * The live-session strip shown above the bottom bar while a workout is in progress and the user
 * is anywhere else in the app: a breathing effort-cyan dot, an uppercase caption, and Resume.
 */
@Composable
fun WorkoutResumeBar(
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pulse = SpotterTheme.pulse
    Surface(color = pulse.panelHigh, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = pulse.hairline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpotterTheme.spacing.lg, vertical = SpotterTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val transition = rememberInfiniteTransition(label = "liveDot")
                val dotAlpha by transition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                    label = "liveDotAlpha",
                )
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(8.dp)
                        .alpha(dotAlpha)
                        .background(pulse.effort, CircleShape),
                )
                Text(
                    text = "WORKOUT IN PROGRESS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = SpotterTheme.spacing.sm),
                )
                TextButton(onClick = onResume) {
                    Text(
                        text = "Resume",
                        style = MaterialTheme.typography.labelLarge,
                        color = pulse.effort,
                    )
                }
            }
        }
    }
}
