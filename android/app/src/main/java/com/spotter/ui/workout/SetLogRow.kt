package com.spotter.ui.workout

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.fieldValue
import com.spotter.ui.theme.formatWeight
import com.spotter.ui.theme.parseToLbs
import com.spotter.util.estimatedOneRM
import design.pulse.ui.components.DataText

/** The set-type vocabulary, in picker order. Mirrors the server's `set_type` enum. */
internal val SET_TYPES = listOf("normal", "warmup", "drop", "failure", "amrap")

/** Badge letter for a non-normal set type (rendered in place of the set number); null = no badge. */
internal fun setTypeBadge(setType: String): String? = when (setType) {
    "warmup" -> "W"
    "drop" -> "D"
    "failure" -> "F"
    "amrap" -> "A"
    else -> null
}

internal fun setTypeLabel(setType: String): String = when (setType) {
    "warmup" -> "Warm-up"
    "drop" -> "Drop"
    "failure" -> "Failure"
    "amrap" -> "AMRAP"
    else -> "Normal"
}

/**
 * One set as an inline-editable row: `[N]  [reps]  [weight]  [✓]`.
 *
 * Reps and weight are mono numeral fields on raised panels; edits commit on focus loss via
 * [onCommit]. The trailing check is the single tap that completes (or un-completes) the
 * set via [onToggleComplete], flushing the current reps/weight at the same time. The
 * weight field is always present so load can be logged even on a bodyweight exercise
 * (e.g. weighted pull-ups); leaving it blank keeps the set bodyweight.
 *
 * The leading set-number cell is the set's type affordance: tapping it opens the type picker
 * (via [onOpenTypePicker], which also offers deletion), and a non-normal type replaces the
 * number with a tinted mono badge (W/D/F/A — warmup amber, drop blue, failure error, AMRAP
 * violet). When [trackRpe] is on, completed rows show a compact RPE entry (1–10, one decimal)
 * committing through [onRpeCommit].
 */
@Composable
fun SetLogRow(
    setLog: SetLogOut,
    onCommit: (reps: Int, weightLbs: Double?) -> Unit,
    onToggleComplete: (reps: Int, weightLbs: Double?) -> Unit,
    onOpenTypePicker: () -> Unit = {},
    trackRpe: Boolean = false,
    onRpeCommit: (Double?) -> Unit = {},
) {
    val weightUnit = LocalWeightUnit.current
    val haptics = LocalHapticFeedback.current
    val pulse = SpotterTheme.pulse
    var repsText by remember(setLog.id) { mutableStateOf(setLog.reps.toString()) }
    var weightText by remember(setLog.id) {
        mutableStateOf(setLog.weight?.let { weightUnit.fieldValue(it) } ?: "")
    }
    var rpeText by remember(setLog.id, setLog.rpe) {
        mutableStateOf(setLog.rpe?.let { formatRpe(it) } ?: "")
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

    val badge = setTypeBadge(setLog.setType)
    val badgeColor = when (setLog.setType) {
        "warmup" -> pulse.streak
        "drop" -> pulse.effort
        "failure" -> MaterialTheme.colorScheme.error
        "amrap" -> pulse.strength
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(rowBg)
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable(onClick = onOpenTypePicker),
                contentAlignment = Alignment.Center,
            ) {
                DataText(
                    text = badge ?: "${setLog.setNumber}",
                    style = SpotterTheme.dataType.numeral.copy(textAlign = TextAlign.Center),
                    color = badgeColor,
                )
            }
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
        if (trackRpe && setLog.completed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 36.dp, bottom = 2.dp),
            ) {
                NumeralField(
                    value = rpeText,
                    onValueChange = { rpeText = sanitizeRpeInput(it) },
                    keyboardType = KeyboardType.Decimal,
                    width = 56.dp,
                    placeholder = "—",
                    onCommit = { onRpeCommit(parseRpe(rpeText)) },
                )
                Spacer(Modifier.width(SpotterTheme.spacing.sm))
                Text(
                    "RPE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Digits + one dot, capped to one decimal place — the RPE input filter. */
internal fun sanitizeRpeInput(raw: String): String {
    val filtered = raw.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    if (firstDot < 0) return filtered.take(2)
    val whole = filtered.substring(0, firstDot).take(2)
    val decimals = filtered.substring(firstDot + 1).replace(".", "").take(1)
    return "$whole.$decimals"
}

/** Parses an RPE entry: blank → null (cleared); otherwise clamped 1.0–10.0, one decimal. */
internal fun parseRpe(text: String): Double? {
    val value = text.trim().toDoubleOrNull() ?: return null
    return (kotlin.math.round(value * 10.0) / 10.0).coerceIn(1.0, 10.0)
}

internal fun formatRpe(value: Double): String =
    if (value == kotlin.math.floor(value)) value.toInt().toString() else "%.1f".format(value)

/**
 * The set-type picker opened from a row's set-number cell: Normal / Warm-up / Drop / Failure /
 * AMRAP, plus "Delete set" (two-step confirm inline; disabled on the exercise's last set —
 * every exercise keeps at least one row).
 */
@Composable
fun SetTypeDialog(
    setLog: SetLogOut,
    canDelete: Boolean,
    onSelectType: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val pulse = SpotterTheme.pulse
    var confirmingDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmingDelete) "Delete set?" else "Set ${setLog.setNumber}") },
        text = {
            if (confirmingDelete) {
                Text("This removes the set from the workout. This can't be undone.")
            } else {
                Column {
                    SET_TYPES.forEach { type ->
                        val selected = type == setLog.setType
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable { onSelectType(type) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            DataText(
                                text = setTypeBadge(type) ?: "${setLog.setNumber}",
                                style = SpotterTheme.dataType.numeral,
                                color = when (type) {
                                    "warmup" -> pulse.streak
                                    "drop" -> pulse.effort
                                    "failure" -> MaterialTheme.colorScheme.error
                                    "amrap" -> pulse.strength
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.width(28.dp),
                            )
                            Spacer(Modifier.width(SpotterTheme.spacing.sm))
                            Text(
                                setTypeLabel(type),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) pulse.effort
                                        else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "Selected",
                                    tint = pulse.effort,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (confirmingDelete) {
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(
                    onClick = { confirmingDelete = true },
                    enabled = canDelete,
                ) {
                    Text(
                        "Delete set",
                        color = if (canDelete) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmingDelete) confirmingDelete = false else onDismiss() }) {
                Text("Cancel")
            }
        },
    )
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
