package net.raphaelgf11.ilo3manager.ui.notifications

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import net.raphaelgf11.ilo3manager.data.HostNotificationSettings
import net.raphaelgf11.ilo3manager.data.NotificationSettingsRepository

private val INTERVAL_OPTIONS_MINUTES = listOf(15, 30, 60, 120)
private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 4242

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun NotificationSettingsDialog(
    hostId: String,
    hostName: String,
    repository: NotificationSettingsRepository,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(repository.settingsFor(hostId)) }

    // Requesting the permission via Compose's rememberLauncherForActivityResult crashes here:
    // MainActivity is a FragmentActivity (required for BiometricPrompt), and FragmentActivity
    // requires requestCode to fit in 16 bits, but the Compose launcher assigns a random 32-bit
    // one. Requesting directly with a fixed, small request code sidesteps that entirely; the
    // result isn't needed back since NotificationHelper already checks the permission before
    // posting anything.
    fun requestEnable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            val activity = context.findActivity()
            if (!granted && activity != null) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE,
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notifications — $hostName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Activer")
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { checked ->
                            settings = settings.copy(enabled = checked)
                            if (checked) requestEnable()
                        },
                    )
                }

                if (settings.enabled) {
                    Text("Intervalle de vérification", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        INTERVAL_OPTIONS_MINUTES.forEach { minutes ->
                            FilterChip(
                                selected = settings.intervalMinutes == minutes,
                                onClick = { settings = settings.copy(intervalMinutes = minutes) },
                                label = { Text(if (minutes < 60) "${minutes}min" else "${minutes / 60}h") },
                            )
                        }
                    }

                    Text("Événements à notifier", style = MaterialTheme.typography.labelLarge)
                    ToggleRow("Échec de connexion", settings.notifyOnConnectionFailure) {
                        settings = settings.copy(notifyOnConnectionFailure = it)
                    }
                    ToggleRow("Dégradation matérielle (ex: alimentation en échec)", settings.notifyOnDegraded) {
                        settings = settings.copy(notifyOnDegraded = it)
                    }
                    ToggleRow("Erreur critique", settings.notifyOnCritical) {
                        settings = settings.copy(notifyOnCritical = it)
                    }
                    ToggleRow("Serveur éteint", settings.notifyOnPoweredOff) {
                        settings = settings.copy(notifyOnPoweredOff = it)
                    }
                    ToggleRow("Serveur allumé", settings.notifyOnPoweredOn) {
                        settings = settings.copy(notifyOnPoweredOn = it)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                repository.save(hostId, settings)
                onDismiss()
            }) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f, fill = true))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
