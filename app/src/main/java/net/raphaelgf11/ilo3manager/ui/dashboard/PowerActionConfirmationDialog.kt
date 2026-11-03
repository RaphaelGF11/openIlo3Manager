package net.raphaelgf11.ilo3manager.ui.dashboard

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import net.raphaelgf11.ilo3manager.R
import net.raphaelgf11.ilo3manager.ilo.PowerAction

@Composable
fun PowerActionConfirmationDialog(
    action: PowerAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (title, message) = when (action) {
        PowerAction.RESET -> R.string.power_confirm_restart_title to R.string.power_confirm_restart_message
        PowerAction.OFF -> R.string.power_confirm_stop_title to R.string.power_confirm_stop_message
        PowerAction.FORCE_OFF -> R.string.power_confirm_force_stop_title to R.string.power_confirm_force_stop_message
        PowerAction.ON -> R.string.power_confirm_start_title to null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { message?.let { Text(stringResource(it)) } },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}
