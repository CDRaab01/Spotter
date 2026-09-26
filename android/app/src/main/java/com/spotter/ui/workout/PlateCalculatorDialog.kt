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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spotter.data.model.EquipmentInventory
import com.spotter.ui.settings.EquipmentOptions
import com.spotter.util.Loading

/**
 * How to load a target weight with the user's own bars and plates (Settings → My equipment),
 * respecting how many of each they own — so it never answers with a plate they don't have or
 * a third pair of 45s from a home gym with two. Weights are shown in the inventory's unit
 * (plates are stamped in one). [singleSided] = T-bar/landmine: plates on one sleeve, no bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlateCalculatorDialog(
    initialWeightLbs: Double,
    inventory: EquipmentInventory,
    onDismiss: () -> Unit,
    singleSided: Boolean = false,
) {
    val unit = inventory.unit
    val isMetric = unit == "kg"
    val initial = Loading.fromLb(initialWeightLbs, unit)
    var targetText by remember {
        mutableStateOf(if (initial > 0.0) EquipmentOptions.num(initial) else "")
    }
    val target = targetText.toDoubleOrNull() ?: 0.0
    val bars = inventory.bars.sortedDescending()
    // Default to the heaviest bar that fits under the target (an EZ bar for a light curl).
    var chosenBar by remember { mutableStateOf(bars.firstOrNull { it <= initial } ?: bars.lastOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plate Calculator") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { raw ->
                        val filtered = raw.filter { c -> c.isDigit() || c == '.' }
                        if (filtered.count { it == '.' } <= 1) targetText = filtered
                    },
                    label = { Text("Target weight ($unit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!singleSided) {
                    Text(
                        "Bar",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (bars.isEmpty()) {
                        Muted("No bars in your equipment — add one in Settings → My equipment.")
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            bars.forEach { bar ->
                                FilterChip(
                                    selected = chosenBar == bar,
                                    onClick = { chosenBar = bar },
                                    label = { Text("${EquipmentOptions.num(bar)} $unit") },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                val bar = if (singleSided) null else chosenBar
                val load = if (target > 0.0 && (singleSided || bar != null)) {
                    Loading.plateLoad(target, bar, inventory.plates)
                } else {
                    null
                }
                when {
                    target <= 0.0 -> Muted("Enter a target weight above.")
                    !singleSided && bar == null -> Unit
                    load == null -> Muted("That's lighter than the bar.")
                    else -> {
                        val short = target - load.total
                        Text(
                            if (load.plates.isEmpty()) "Just the bar."
                            else if (singleSided) "On the sleeve:" else "Each side:",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        if (short > Loading.EPS) {
                            Text(
                                "Closest you can load: ${EquipmentOptions.num(load.total)} $unit " +
                                    "(${EquipmentOptions.num(short)} $unit short)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (load.plates.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                load.plates.forEach { (plate, count) ->
                                    repeat(count) { PlateCircle(plate = plate, isMetric = isMetric) }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            load.plates.forEach { (plate, count) ->
                                Muted("× $count  ${EquipmentOptions.num(plate)} $unit")
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
private fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PlateCircle(plate: Double, isMetric: Boolean) {
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

private fun plateInfo(plate: Double, isMetric: Boolean): PlateInfo {
    val label = EquipmentOptions.num(plate)
    return if (isMetric) when (plate) {
        25.0 -> PlateInfo(Color(0xFFD32F2F), Color.White, label)
        20.0 -> PlateInfo(Color(0xFF1976D2), Color.White, label)
        15.0 -> PlateInfo(Color(0xFFF9A825), Color.Black, label)
        10.0 -> PlateInfo(Color(0xFF388E3C), Color.White, label)
        5.0 -> PlateInfo(Color.White, Color.Black, label)
        2.5 -> PlateInfo(Color(0xFF212121), Color.White, label)
        1.25 -> PlateInfo(Color(0xFF9E9E9E), Color.Black, label)
        else -> PlateInfo(Color.Gray, Color.White, label)
    } else when (plate) {
        45.0 -> PlateInfo(Color(0xFFD32F2F), Color.White, label)
        35.0 -> PlateInfo(Color(0xFF1976D2), Color.White, label)
        25.0 -> PlateInfo(Color(0xFF212121), Color.White, label)
        15.0 -> PlateInfo(Color(0xFFF9A825), Color.Black, label)
        10.0 -> PlateInfo(Color(0xFF388E3C), Color.White, label)
        5.0 -> PlateInfo(Color.White, Color.Black, label)
        2.5 -> PlateInfo(Color(0xFFF9A825), Color.Black, label)
        1.25 -> PlateInfo(Color(0xFF9E9E9E), Color.Black, label)
        else -> PlateInfo(Color.Gray, Color.White, label)
    }
}
