package net.raphaelgf11.ilo3manager.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import net.raphaelgf11.ilo3manager.R
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
        title = { Text(stringResource(R.string.update_available_title, update.version)) },
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
                        stringResource(R.string.update_download_size, update.apkSizeBytes / 1_048_576.0),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSeeMore) { Text(stringResource(R.string.update_see_more)) } },
        dismissButton = {
            Column {
                TextButton(onClick = onLater) { Text(stringResource(R.string.update_later)) }
                TextButton(onClick = onNeverAsk) { Text(stringResource(R.string.update_never_ask)) }
            }
        },
    )
}
