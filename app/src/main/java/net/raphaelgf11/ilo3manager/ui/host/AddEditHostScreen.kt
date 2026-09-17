package net.raphaelgf11.ilo3manager.ui.host

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.data.AuthMethod
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ssh.SshKeyGenerator
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditHostScreen(
    repository: HostRepository,
    existingHost: SshHost?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf(existingHost?.name ?: "") }
    var hostname by remember { mutableStateOf(existingHost?.hostname ?: "") }
    var port by remember { mutableStateOf((existingHost?.port ?: 22).toString()) }
    var httpsPort by remember { mutableStateOf((existingHost?.httpsPort ?: 443).toString()) }
    var username by remember { mutableStateOf(existingHost?.username ?: "") }
    var authMethod by remember { mutableStateOf(existingHost?.authMethod ?: AuthMethod.PASSWORD) }
    // Secrets are never re-displayed when editing an existing host: these start blank and, if
    // left blank on save, the previously stored secret is kept unchanged.
    var password by remember { mutableStateOf("") }
    var privateKey by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                privateKey = BufferedReader(InputStreamReader(stream)).readText()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingHost == null) "Nouvel hôte" else "Modifier l'hôte") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer()
            OutlinedTextField(
                value = hostname,
                onValueChange = { hostname = it },
                label = { Text("Adresse (IP ou nom d'hôte)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer()
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() } },
                label = { Text("Port SSH") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer()
            OutlinedTextField(
                value = httpsPort,
                onValueChange = { httpsPort = it.filter { c -> c.isDigit() } },
                label = { Text("Port HTTPS (interface web iLO)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer()
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Utilisateur") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer()

            Text("Méthode d'authentification")
            AuthMethod.entries.forEach { method ->
                Row {
                    RadioButton(selected = authMethod == method, onClick = { authMethod = method })
                    Text(
                        text = when (method) {
                            AuthMethod.PASSWORD -> "Mot de passe"
                            AuthMethod.PRIVATE_KEY -> "Clé privée"
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            when (authMethod) {
                AuthMethod.PASSWORD -> {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = {
                            Text(
                                if (existingHost == null) "Mot de passe" else "Nouveau mot de passe (laisser vide pour ne pas changer)",
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AuthMethod.PRIVATE_KEY -> {
                    Row {
                        OutlinedButton(onClick = { filePicker.launch("*/*") }) {
                            Text("Importer un fichier de clé")
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val generated = withContext(Dispatchers.Default) {
                                        SshKeyGenerator.generateRsaKeyPair(comment = username.ifBlank { "ilo3manager" })
                                    }
                                    privateKey = generated.privateKeyPem
                                    publicKey = generated.publicKeyOpenSsh
                                }
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text("Générer une paire de clés")
                        }
                    }
                    Spacer()
                    OutlinedTextField(
                        value = privateKey,
                        onValueChange = { privateKey = it },
                        label = {
                            Text(
                                if (existingHost == null) {
                                    "Clé privée (collez le contenu ou importez un fichier)"
                                } else {
                                    "Nouvelle clé privée (laisser vide pour ne pas changer)"
                                },
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        minLines = 4,
                    )
                    Spacer()
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = {
                            Text(
                                if (existingHost == null) {
                                    "Phrase secrète de la clé (optionnel)"
                                } else {
                                    "Nouvelle phrase secrète (laisser vide pour ne pas changer)"
                                },
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (publicKey.isNotBlank()) {
                        Spacer()
                        Text("Clé publique à ajouter dans l'iLO (Administration > Sécurité > SSH) :")
                        OutlinedTextField(
                            value = publicKey,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                        )
                    }
                }
            }

            Spacer()
            Button(
                onClick = {
                    val keyChanged = privateKey.isNotBlank()
                    val host = SshHost(
                        id = existingHost?.id ?: java.util.UUID.randomUUID().toString(),
                        name = name,
                        hostname = hostname,
                        port = port.toIntOrNull() ?: 22,
                        httpsPort = httpsPort.toIntOrNull() ?: 443,
                        username = username,
                        authMethod = authMethod,
                        password = password.ifBlank { existingHost?.password ?: "" },
                        privateKey = if (keyChanged) privateKey else existingHost?.privateKey ?: "",
                        privateKeyPassphrase = if (keyChanged) passphrase else existingHost?.privateKeyPassphrase ?: "",
                        publicKey = if (keyChanged) publicKey else existingHost?.publicKey ?: "",
                    )
                    repository.saveHost(host)
                    onDone()
                },
                enabled = name.isNotBlank() && hostname.isNotBlank() && username.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Enregistrer")
            }
        }
    }
}

@Composable
private fun Spacer() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
}
