package net.raphaelgf11.ilo3manager.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import net.raphaelgf11.ilo3manager.update.AvailableUpdate

/**
 * Shown at launch when a newer release exists.
 *
 * "Voir plus" leads to the settings screen rather than straight to a download: that is where the
 * release notes and the install permission live, and an update is not something to start by
 * accident on a phone.
 */
@Composable
fun UpdateAvailableDialog(
    update: AvailableUpdate,
    onLater: () -> Unit,
    onNeverAsk: () -> Unit,
    onSeeMore: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("Version ${update.version} disponible") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (update.notes.isNotBlank()) {
                    Text(
                        update.notes.lineSequence().take(8).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (update.apkSizeBytes > 0) {
                    Text(
                        "Téléchargement : %.1f Mo".format(update.apkSizeBytes / 1_048_576.0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSeeMore) { Text("Voir plus") } },
        dismissButton = {
            Column {
                TextButton(onClick = onLater) { Text("Plus tard") }
                TextButton(onClick = onNeverAsk) { Text("Ne plus demander") }
            }
        },
    )
}
