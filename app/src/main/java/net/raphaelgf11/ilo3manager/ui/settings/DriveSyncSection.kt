package net.raphaelgf11.ilo3manager.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.backup.BackupSecretStore
import net.raphaelgf11.ilo3manager.backup.DriveSync
import net.raphaelgf11.ilo3manager.data.HostRepository
import java.text.DateFormat
import java.util.Date

/**
 * Backup to the private Drive application folder.
 *
 * The secret is asked for up front and cached on the device, because it is what makes the backup
 * readable again on a different phone — a device-bound key could not do that. Restoring asks for
 * confirmation before overwriting, since it replaces every host on this device.
 */
@Composable
fun DriveSyncSection(hostRepository: HostRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val secretStore = remember { BackupSecretStore(context) }

    var account by remember { mutableStateOf(DriveSync.signedInAccount(context)?.email) }
    var hasConsent by remember { mutableStateOf(DriveSync.hasDriveConsent(context)) }
    var secret by remember { mutableStateOf(secretStore.secret ?: "") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastSync by remember { mutableStateOf(secretStore.lastSyncAt) }

    val signIn = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // The status code is the only thing that distinguishes a misconfigured Cloud project from
        // a user who simply cancelled, so it must reach the screen rather than be flattened into
        // one generic message.
        runCatching { GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java) }
            .onSuccess {
                account = it?.email
                hasConsent = DriveSync.hasDriveConsent(context)
                error = null
            }
            .onFailure { failure ->
                val status = (failure as? ApiException)?.statusCode
                error = when (status) {
                    CommonStatusCodes.DEVELOPER_ERROR ->
                        "Erreur de configuration (code 10) : le client OAuth Android du projet " +
                            "Google Cloud ne correspond pas au paquet ou à l'empreinte SHA-1 de " +
                            "cette build."
                    CommonStatusCodes.NETWORK_ERROR -> "Erreur réseau (code 7)."
                    GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Connexion annulée."
                    else -> "Échec de la connexion Google (code ${status ?: "inconnu"}) : ${failure.message}"
                }
            }
    }

    fun run(label: String, block: suspend () -> String) {
        busy = true
        error = null
        message = null
        scope.launch {
            try {
                message = withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                error = e.message ?: "Échec de $label"
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Sauvegarde Google Drive", style = MaterialTheme.typography.titleMedium)
        Text(
            "La configuration est chiffrée sur l'appareil, puis déposée dans l'espace privé de " +
                "l'application sur votre Drive : elle n'apparaît pas dans l'interface Drive et " +
                "n'est pas téléchargeable depuis celle-ci.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (account == null) {
            Button(
                onClick = { signIn.launch(DriveSync.signInClient(context).signInIntent) },
                enabled = !busy,
            ) {
                Text("Connecter un compte Google")
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("Compte : $account", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = {
                        // Revoking rather than merely signing out, so the next connection asks for
                        // consent again instead of silently reusing a previous grant.
                        DriveSync.revokeAccess(context) {
                            account = null
                            hasConsent = false
                            message = "Compte déconnecté."
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("Déconnecter")
                }
            }

            // Signing in and granting the Drive scope are separate: without this the account looks
            // connected while every backup would fail for lack of permission.
            if (!hasConsent) {
                Text(
                    "Ce compte est connecté mais n'a pas accordé l'accès à l'espace privé Drive : " +
                        "la sauvegarde échouerait.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = {
                        // Re-running sign-in is what re-opens the consent dialog for a scope that
                        // was dismissed; requestPermissions would report its result outside this
                        // screen, where it could not be observed.
                        DriveSync.revokeAccess(context) {
                            signIn.launch(DriveSync.signInClient(context).signInIntent)
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("Autoriser l'accès à Google Drive")
                }
            }
        }

        OutlinedTextField(
            value = secret,
            onValueChange = {
                secret = it
                secretStore.secret = it
            },
            label = { Text("Phrase secrète ou code de chiffrement") },
            visualTransformation = PasswordVisualTransformation(),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Elle seule permet de relire la sauvegarde, y compris sur un autre téléphone. " +
                "Conservée chiffrée sur cet appareil pour ne pas vous la redemander — mais " +
                "oubliée, la sauvegarde devient définitivement illisible.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )

        if (lastSync > 0) {
            Text(
                "Dernière sauvegarde : " + DateFormat.getDateTimeInstance().format(Date(lastSync)),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    run("la sauvegarde") {
                        DriveSync.backup(context, hostRepository, secret)
                        val now = System.currentTimeMillis()
                        secretStore.lastSyncAt = now
                        lastSync = now
                        "Sauvegarde envoyée."
                    }
                },
                enabled = !busy && account != null && hasConsent && secret.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Sauvegarder")
            }
            OutlinedButton(
                onClick = {
                    run("la restauration") {
                        val hosts = DriveSync.fetchBackup(context, secret)
                        // Written only once decryption has succeeded, so a wrong secret or an
                        // unreadable backup can never leave the device half-restored.
                        hostRepository.reorderHosts(hosts)
                        "${hosts.size} hôte(s) restauré(s)."
                    }
                },
                enabled = !busy && account != null && hasConsent && secret.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Restaurer")
            }
        }

        if (busy) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  Opération en cours…", style = MaterialTheme.typography.bodySmall)
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
