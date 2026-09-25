package net.raphaelgf11.ilo3manager.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Asks before destroying something the user cannot get back.
 *
 * Deletions here are immediate and permanent — a host carries its credentials and a network its
 * private key, neither of which is anywhere else — and the buttons that trigger them sit next to
 * the edit button, on a list that is scrolled and dragged with the same finger.
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                // Coloured as the destructive action it is, so the two buttons are not read as
                // interchangeable at a glance.
                Text("Supprimer", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
