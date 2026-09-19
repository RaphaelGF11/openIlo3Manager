package net.raphaelgf11.ilo3manager.ui.hostdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Offers to turn IPMI on after the first SSH connection to a host that isn't using it.
 *
 * Enabling IPMI opens UDP 623 on the management processor, which is a real exposure — the IPMI 2.0
 * RAKP handshake hands out a password hash to anyone who asks, allowing offline cracking, and the
 * protocol has no way to avoid that. So this always asks, always states the trade-off, and offers
 * a permanent "no".
 */
@Composable
fun IpmiSuggestionDialog(
    canEnable: Boolean,
    busy: Boolean,
    error: String?,
    onEnable: () -> Unit,
    onLater: () -> Unit,
    onNeverAsk: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onLater() },
        title = { Text("Activer IPMI pour cet hôte ?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "L'onglet Alim passerait entièrement par IPMI : l'état et les actions " +
                        "d'alimentation répondent en une fraction de seconde au lieu de plusieurs " +
                        "secondes, et aucune session SSH n'est ouverte pour l'afficher.",
                )
                Text(
                    "En contrepartie, cela ouvre le port UDP 623 sur l'iLO. IPMI est un protocole " +
                        "ancien dont l'authentification livre, par conception, une empreinte du mot " +
                        "de passe à quiconque la demande — elle peut ensuite être attaquée hors " +
                        "ligne. À n'activer que sur un réseau d'administration de confiance.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!canEnable) {
                    Text(
                        "Cet hôte n'a pas de mot de passe enregistré. IPMI s'authentifie par mot " +
                            "de passe : renseignez-en un dans l'onglet Authentification de la fiche " +
                            "de l'hôte avant de l'activer.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onEnable, enabled = canEnable && !busy) {
                Text("Activer")
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onLater, enabled = !busy) { Text("Plus tard") }
                TextButton(onClick = onNeverAsk, enabled = !busy) { Text("Ne plus proposer") }
            }
        },
    )
}
