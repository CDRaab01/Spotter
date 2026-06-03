package com.spotter.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spotter.util.WeightUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlateCalculatorDialog(
    initialWeight: Float,
    weightUnit: WeightUnit,
    onDismiss: () -> Unit,
) {
    val isMetric = weightUnit == WeightUnit.KG
    val unitLabel = if (isMetric) "kg" else "lb"

    val barOptions = if (isMetric)
        listOf("20 kg" to 20f, "15 kg" to 15f, "10 kg" to 10f)
    else
        listOf("45 lb" to 45f, "35 lb" to 35f, "15 lb" to 15f)

    val plateOptions = if (isMetric)
        listOf(20f, 15f, 10f, 5f, 2.5f, 1.25f)
    else
        listOf(45f, 35f, 25f, 10f, 5f, 2.5f)

    var targetText by remember {
        mutableStateOf(if (initialWeight > 0f) "%.0f".format(initialWeight) else "")
    }
    var selectedBarIdx by remember { mutableIntStateOf(0) }

    val barWeight = barOptions[selectedBarIdx].second
    val total = targetText.toFloatOrNull() ?: 0f
    val perSide = ((total - barWeight).coerceAtLeast(0f)) / 2f
    val plates = plateSides(total, barWeight, plateOptions)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plate Calculator") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { raw ->
                        // Allow digits and at most one decimal point
                        val filtered = raw.filter { c -> c.isDigit() || c == '.' }
                        val dotCount = filtered.count { it == '.' }
                        targetText = if (dotCount <= 1) filtered else targetText
                    },
                    label = { Text("Target weight ($unitLabel)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "Bar weight",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    barOptions.forEachIndexed { idx, (label, _) ->
                        FilterChip(
                            selected = selectedBarIdx == idx,
                            onClick = { selectedBarIdx = idx },
                            label = { Text(label) },
                        )
                    }
                }

                HorizontalDivider()

                when {
                    total < 0.01f -> Text(
                        "Enter a target weight above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    total <= barWeight -> Text(
                        "Weight is ≤ bar weight — no plates needed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        val achievablePerSide = plates.fold(0f) { acc, (p, c) -> acc + p * c }
                        val achievableTotal = barWeight + achievablePerSide * 2
                        val residual = total - achievableTotal
                        Text(
                            "Per side: ${"%.2f".format(perSide).trimEnd('0').trimEnd('.')} $unitLabel",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (residual > 0.01f) {
                            Text(
                                "Nearest achievable: ${"%.1f".format(achievableTotal)} $unitLabel (${
                                    "%.2f".format(residual).trimEnd('0').trimEnd('.')
                                } $unitLabel short)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            plates.forEach { (plate, count) ->
                                repeat(count) {
                                    PlateCircle(plate = plate, isMetric = isMetric)
                                }
                            }
                        }
                        if (plates.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            plates.forEach { (plate, count) ->
                                val plateLabel = if (plate % 1f < 0.01f)
                                    "${plate.toLong()} $unitLabel"
                                else "$plate $unitLabel"
                                Text(
                                    "× $count  $plateLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun PlateCircle(plate: Float, isMetric: Boolean) {
    val info = plateInfo(plate, isMetric)
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(info.bg, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = info.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = info.textColor,
        )
    }
}

private data class PlateInfo(val bg: Color, val textColor: Color, val label: String)

private fun plateInfo(plate: Float, isMetric: Boolean): PlateInfo =
    if (isMetric) when (plate) {
        20f   -> PlateInfo(Color(0xFFD32F2F), Color.White, "20")
        15f   -> PlateInfo(Color(0xFF1976D2), Color.White, "15")
        10f   -> PlateInfo(Color(0xFFF9A825), Color.Black, "10")
        5f    -> PlateInfo(Color(0xFF388E3C), Color.White, "5")
        2.5f  -> PlateInfo(Color.White,       Color.Black, "2.5")
        1.25f -> PlateInfo(Color(0xFF424242), Color.White, "1.25")
        else  -> PlateInfo(Color.Gray, Color.White, plate.toString())
    } else when (plate) {
        45f  -> PlateInfo(Color(0xFFD32F2F), Color.White, "45")
        35f  -> PlateInfo(Color(0xFF1976D2), Color.White, "35")
        25f  -> PlateInfo(Color(0xFF212121), Color.White, "25")
        10f  -> PlateInfo(Color(0xFF388E3C), Color.White, "10")
        5f   -> PlateInfo(Color.White,       Color.Black, "5")
        2.5f -> PlateInfo(Color(0xFFF9A825), Color.Black, "2.5")
        else -> PlateInfo(Color.Gray, Color.White, plate.toString())
    }

private fun plateSides(total: Float, bar: Float, plates: List<Float>): List<Pair<Float, Int>> {
    var remaining = (total - bar).coerceAtLeast(0f) / 2f
    return plates.mapNotNull { plate ->
        val count = (remaining / plate).toInt()
        if (count > 0) {
            remaining -= count * plate
            plate to count
        } else null
    }
}
