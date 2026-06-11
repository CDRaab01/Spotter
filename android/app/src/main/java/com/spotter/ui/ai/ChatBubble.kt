package com.spotter.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spotter.data.model.ChatMessage
import com.spotter.ui.theme.SpotterTheme

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    val pulse = SpotterTheme.pulse
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpotterTheme.spacing.md, vertical = SpotterTheme.spacing.xs),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        val shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isUser) 16.dp else 4.dp,
            bottomEnd = if (isUser) 4.dp else 16.dp,
        )
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (isUser) pulse.effortDim else pulse.panel,
                    shape = shape,
                )
                .border(1.dp, if (isUser) pulse.effort.copy(alpha = 0.25f) else pulse.hairline, shape)
                .padding(SpotterTheme.spacing.md),
        ) {
            Text(
                text = message.content,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
