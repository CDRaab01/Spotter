package com.spotter.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spotter.data.model.SetLogOut
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.formatWeightNullable

@Composable
fun SetLogRow(
    setLog: SetLogOut,
    isActive: Boolean,
    currentReps: Int,
    onTap: () -> Unit,
    onEditWeight: () -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    val displayReps = if (isActive) currentReps else setLog.reps
    val bgColor = when {
        isActive         -> MaterialTheme.colorScheme.tertiary
        setLog.completed -> MaterialTheme.colorScheme.primary
        else             -> MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor: Color = if (isActive || setLog.completed) Color.White
                           else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Set ${setLog.setNumber}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(bgColor)
                .then(
                    if (!setLog.completed) Modifier.clickable(onClick = onTap)
                    else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$displayReps",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = weightUnit.formatWeightNullable(setLog.weight),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onEditWeight),
        )
    }
}
