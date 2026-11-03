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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.raphaelgf11.ilo3manager.R

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
        title = { Text(stringResource(R.string.ipmi_suggestion_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.ipmi_suggestion_speed),
                )
                Text(
                    stringResource(R.string.ipmi_suggestion_risk),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!canEnable) {
                    Text(
                        stringResource(R.string.ipmi_suggestion_missing_password),
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
                Text(stringResource(R.string.ipmi_suggestion_enable))
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onLater, enabled = !busy) { Text(stringResource(R.string.ipmi_suggestion_later)) }
                TextButton(onClick = onNeverAsk, enabled = !busy) { Text(stringResource(R.string.ipmi_suggestion_never)) }
            }
        },
    )
}
