package com.spotter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spotter.ui.theme.SpotterTheme

/** The app mark: a rounded gradient tile with a dumbbell glyph. Used on auth/onboarding. */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(SpotterTheme.brand.heroGradient, RoundedCornerShape(size / 3.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.FitnessCenter,
            contentDescription = "Spotter",
            tint = Color.White,
            modifier = Modifier.size(size / 2),
        )
    }
}
