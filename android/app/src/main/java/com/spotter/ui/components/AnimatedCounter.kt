package com.spotter.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * MIGRATION SHIM over [TickerNumber] — same rolling-count behaviour, now on the data easing.
 * New code should call [TickerNumber] with a `SpotterTheme.dataType` style directly.
 */
@Deprecated("Use TickerNumber with a SpotterTheme.dataType style.")
@Composable
fun AnimatedCounter(
    target: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    fontWeight: FontWeight? = null,
    suffix: String = "",
    prefix: String = "",
    durationMillis: Int = 700,
) {
    TickerNumber(
        target = target,
        modifier = modifier,
        style = if (fontWeight != null) style.copy(fontWeight = fontWeight) else style,
        prefix = prefix,
        suffix = suffix,
    )
}
