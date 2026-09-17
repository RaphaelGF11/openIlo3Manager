package net.raphaelgf11.ilo3manager.ui.dashboard

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import net.raphaelgf11.ilo3manager.ilo.PowerAction

@Composable
fun PowerActionConfirmationDialog(
    action: PowerAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, message) = when (action) {
        PowerAction.RESET -> "Redémarrer le serveur ?" to "Le serveur va redémarrer immédiatement."
        PowerAction.OFF -> "Arrêter le serveur ?" to "Demande d'arrêt normal (arrêt propre du système d'exploitation)."
        PowerAction.FORCE_OFF -> "Forcer l'arrêt du serveur ?" to "Coupe l'alimentation immédiatement, sans arrêt propre. Risque de perte de données."
        PowerAction.ON -> "Démarrer le serveur ?" to ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Confirmer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
