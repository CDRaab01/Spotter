package com.spotter.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spotter.ui.components.DataText
import com.spotter.ui.theme.LocalWeightUnit
import com.spotter.ui.theme.SpotterTheme
import com.spotter.ui.theme.formatWeight
import com.spotter.util.warmupSets

/**
 * Read-only helper showing ramp-up sets for a working weight. Does not mutate or
 * log any sets — it just tells the lifter how to warm up into the working load.
 */
@Composable
fun WarmUpDialog(
    workingWeightLbs: Double,
    onDismiss: () -> Unit,
) {
    val weightUnit = LocalWeightUnit.current
    val sets = warmupSets(workingWeightLbs)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Warm-up sets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Ramp up to ${weightUnit.formatWeight(workingWeightLbs)} before your working sets:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                sets.forEach { set ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        DataText(
                            text = "${set.percent}%",
                            style = SpotterTheme.dataType.numeral,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DataText(
                            text = "${set.reps} × ${weightUnit.formatWeight(set.weightLbs)}",
                            style = SpotterTheme.dataType.numeral,
                        )
                    }
                }
                HorizontalDivider()
                Text(
                    "Then: working sets at ${weightUnit.formatWeight(workingWeightLbs)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
