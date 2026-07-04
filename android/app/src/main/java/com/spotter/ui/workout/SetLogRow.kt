package com.spotter.ui.workout

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.spotter.data.model.SetLogOut
import design.pulse.ui.components.DataText
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.fieldValue
import com.spotter.ui.theme.formatWeight
import com.spotter.ui.theme.parseToLbs
import com.spotter.util.estimatedOneRM

/**
 * One set as an inline-editable row: `[N]  [reps]  [weight]  [✓]`.
 *
 * Reps and weight are mono numeral fields on raised panels; edits commit on focus loss via
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
    val pulse = SpotterTheme.pulse
    var repsText by remember(setLog.id) { mutableStateOf(setLog.reps.toString()) }
    var weightText by remember(setLog.id) {
        mutableStateOf(setLog.weight?.let { weightUnit.fieldValue(it) } ?: "")
    }

    fun reps(): Int = repsText.toIntOrNull() ?: setLog.reps
    fun weightLbs(): Double? = if (weightText.isBlank()) null else weightUnit.parseToLbs(weightText)

    // Completed rows get a soft recovery wash; the check springs as it flips.
    val rowBg by animateColorAsState(
        targetValue = if (setLog.completed) pulse.recoveryDim.copy(alpha = 0.55f) else Color.Transparent,
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
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DataText(
                text = "${setLog.setNumber}",
                style = SpotterTheme.dataType.numeral.copy(textAlign = TextAlign.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp),
            )
            NumeralField(
                value = repsText,
                onValueChange = { repsText = it.filter { c -> c.isDigit() } },
                keyboardType = KeyboardType.Number,
                width = 76.dp,
                onCommit = { onCommit(reps(), weightLbs()) },
            )
            Spacer(Modifier.width(SpotterTheme.spacing.sm))
            NumeralField(
                value = weightText,
                onValueChange = { weightText = it.filter { c -> c.isDigit() || c == '.' } },
                keyboardType = KeyboardType.Decimal,
                width = 96.dp,
                placeholder = "BW",
                onCommit = { onCommit(reps(), weightLbs()) },
            )
            Spacer(Modifier.width(SpotterTheme.spacing.sm))
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
                    tint = if (setLog.completed) pulse.recovery
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
                modifier = Modifier.padding(start = 36.dp, bottom = SpotterTheme.spacing.xs),
            )
        }
    }
}

/** A centered mono numeral input on a raised panel — the PULSE replacement for outlined fields. */
@Composable
private fun NumeralField(
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType,
    width: Dp,
    onCommit: () -> Unit,
    placeholder: String? = null,
) {
    val pulse = SpotterTheme.pulse
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = SpotterTheme.dataType.numeral.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(pulse.effort),
        modifier = Modifier
            .width(width)
            .onFocusChanged { if (!it.isFocused) onCommit() },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(pulse.panelHigh)
                    .height(40.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    DataText(
                        text = placeholder,
                        style = SpotterTheme.dataType.numeral,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
}
