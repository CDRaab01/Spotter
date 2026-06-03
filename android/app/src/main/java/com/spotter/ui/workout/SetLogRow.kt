package com.spotter.ui.workout

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spotter.data.model.SetLogOut
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.fieldValue
import com.spotter.ui.theme.formatWeight
import com.spotter.ui.theme.parseToLbs
import com.spotter.util.estimatedOneRM

/**
 * One set as an inline-editable row: `[N]  [reps]  [weight]  [✓]`.
 *
 * Reps and weight are prefilled and editable in place; edits commit on focus loss via
 * [onCommit]. The trailing check is the single tap that completes (or un-completes) the
 * set via [onToggleComplete], flushing the current reps/weight at the same time. The
 * weight field is always present so load can be logged even on a bodyweight exercise
 * (e.g. weighted pull-ups); leaving it blank keeps the set bodyweight.
 */
@Composable
fun SetLogRow(
    setLog: SetLogOut,
    onCommit: (reps: Int, weightLbs: Double?) -> Unit,
    onToggleComplete: (reps: Int, weightLbs: Double?) -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    val haptics = LocalHapticFeedback.current
    var repsText by remember(setLog.id) { mutableStateOf(setLog.reps.toString()) }
    var weightText by remember(setLog.id) {
        mutableStateOf(setLog.weight?.let { weightUnit.fieldValue(it) } ?: "")
    }

    fun reps(): Int = repsText.toIntOrNull() ?: setLog.reps
    fun weightLbs(): Double? = if (weightText.isBlank()) null else weightUnit.parseToLbs(weightText)

    // Completed rows get a soft accent wash; the check springs as it flips.
    val rowBg by animateColorAsState(
        targetValue = if (setLog.completed) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        } else Color.Transparent,
        label = "rowBg",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (setLog.completed) 1f else 0.85f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "checkScale",
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(rowBg)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${setLog.setNumber}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(36.dp),
            )
            OutlinedTextField(
                value = repsText,
                onValueChange = { repsText = it.filter { c -> c.isDigit() } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                modifier = Modifier
                    .width(76.dp)
                    .onFocusChanged { if (!it.isFocused) onCommit(reps(), weightLbs()) },
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = weightText,
                onValueChange = { weightText = it.filter { c -> c.isDigit() || c == '.' } },
                singleLine = true,
                placeholder = {
                    Text(
                        "BW",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
                modifier = Modifier
                    .width(96.dp)
                    .onFocusChanged { if (!it.isFocused) onCommit(reps(), weightLbs()) },
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                if (!setLog.completed) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                onToggleComplete(reps(), weightLbs())
            }) {
                Icon(
                    imageVector = if (setLog.completed) Icons.Filled.CheckCircle
                                  else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (setLog.completed) "Mark set incomplete"
                                         else "Mark set complete",
                    tint = if (setLog.completed) MaterialTheme.colorScheme.secondary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.scale(checkScale),
                )
            }
        }
        if (setLog.completed && setLog.reps > 1 && setLog.weight != null) {
            Text(
                text = "≈ ${weightUnit.formatWeight(estimatedOneRM(setLog.weight, setLog.reps))} est. 1RM",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 36.dp, bottom = 4.dp),
            )
        }
    }
}
