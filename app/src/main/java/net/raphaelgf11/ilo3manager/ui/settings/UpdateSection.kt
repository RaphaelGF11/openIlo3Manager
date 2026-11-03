package net.raphaelgf11.ilo3manager.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import net.raphaelgf11.ilo3manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.BuildConfig
import net.raphaelgf11.ilo3manager.data.SettingsRepository
import net.raphaelgf11.ilo3manager.update.ApkInstaller
import net.raphaelgf11.ilo3manager.update.AvailableUpdate
import net.raphaelgf11.ilo3manager.update.UpdateChecker

/** Checks GitHub for a newer release, then downloads and hands it to the system installer. */
@Composable
fun UpdateSection(settings: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AvailableUpdate?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var dialogEnabled by remember { mutableStateOf(settings.updateDialogEnabled) }

    fun check() {
        checking = true
        error = null
        status = null
        scope.launch {
            try {
                update = withContext(Dispatchers.IO) { UpdateChecker.check(context) }
                if (update == null) status = context.getString(R.string.updates_up_to_date)
            } catch (e: Exception) {
                error = e.message ?: context.getString(R.string.updates_check_failed)
            } finally {
                checking = false
            }
        }
    }

    LaunchedEffect(Unit) { check() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.updates_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.updates_installed_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)

        update?.let { available ->
            Text(stringResource(R.string.updates_available_version, available.version), style = MaterialTheme.typography.titleSmall)
            if (available.notes.isNotBlank()) {
                Text(
                    available.notes.lineSequence().take(6).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (downloading) {
            // An indeterminate bar when the server gives no length, rather than a bar stuck at zero.
            if (progress >= 0f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = ::check, enabled = !checking && !downloading, modifier = Modifier.weight(1f)) {
                Text(stringResource(if (checking) R.string.updates_checking else R.string.updates_check))
            }
            update?.let { available ->
                Button(
                    onClick = {
                        if (!ApkInstaller.canRequestInstall(context)) {
                            // Asking for the permission first avoids downloading tens of megabytes
                            // only to be blocked at the last step.
                            error = context.getString(R.string.updates_install_permission)
                            ApkInstaller.openInstallPermissionSettings(context)
                            return@Button
                        }
                        downloading = true
                        error = null
                        status = context.getString(R.string.updates_downloading)
                        scope.launch {
                            try {
                                val apk = withContext(Dispatchers.IO) {
                                    ApkInstaller.download(context, available.apkUrl) { progress = it }
                                }
                                status = context.getString(R.string.updates_installing)
                                ApkInstaller.install(context, apk)
                            } catch (e: Exception) {
                                error = e.message ?: context.getString(R.string.updates_install_failed)
                            } finally {
                                downloading = false
                            }
                        }
                    },
                    enabled = !downloading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.updates_action))
                }
            }
        }

        update?.takeIf { it.releaseUrl.isNotBlank() }?.let { available ->
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(available.releaseUrl)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.updates_release_notes))
            }
        }

        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(stringResource(R.string.updates_on_startup))
                Text(
                    stringResource(R.string.updates_startup_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = dialogEnabled,
                onCheckedChange = {
                    dialogEnabled = it
                    settings.updateDialogEnabled = it
                },
            )
        }

        Text(
            stringResource(R.string.updates_signature_help),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
