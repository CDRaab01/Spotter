package com.spotter.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * A number that rolls up from 0 to [target] when first composed (and animates between values on
 * change). Used for the payoff stats — streaks, volume, sets — so they feel earned rather than
 * just appearing.
 */
@Composable
fun AnimatedCounter(
    target: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = FontWeight.ExtraBold,
    suffix: String = "",
    prefix: String = "",
    durationMillis: Int = 700,
) {
    var start by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { start = target }
    LaunchedEffect(target) { start = target }
    val value by animateIntAsState(
        targetValue = start,
        animationSpec = tween(durationMillis, easing = LinearOutSlowInEasing),
        label = "counter",
    )
    Text(
        text = "$prefix$value$suffix",
        modifier = modifier,
        style = style,
        fontWeight = fontWeight,
    )
}
