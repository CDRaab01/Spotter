package com.spotter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The app's standard surface: rounded, softly elevated, with an optional gradient background or
 * accent border. Replaces the scattered raw `Card { }` usages so cards look consistent and a bit
 * more premium. When [onClick] is provided the whole card is tappable with a press-scale.
 */
@Composable
fun SpotterCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    gradient: Brush? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = null,
    elevation: Dp = 2.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val interaction = remember { MutableInteractionSource() }
    val baseModifier = if (onClick != null) {
        modifier.pressScale(interaction)
    } else modifier

    val inner: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = if (gradient != null) {
                Modifier
                    .background(gradient, shape)
                    .padding(contentPadding)
            } else Modifier.padding(contentPadding),
            content = content,
        )
    }

    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = baseModifier,
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (gradient != null) Color.Transparent else containerColor,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            border = border,
            interactionSource = interaction,
        ) { Column { inner() } }
    } else {
        Card(
            modifier = baseModifier,
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (gradient != null) Color.Transparent else containerColor,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            border = border,
        ) { Column { inner() } }
    }
}
