package net.raphaelgf11.ilo3manager.ui.host

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import net.raphaelgf11.ilo3manager.ssh.HostSessionStore
import net.raphaelgf11.ilo3manager.ssh.SshKeyGenerator
import java.io.BufferedReader
import java.io.InputStreamReader

private val TAB_TITLES = listOf("Général", "Authentification", "IPMI")

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

    var selectedTab by remember { mutableIntStateOf(0) }

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
    var ipmiEnabled by remember { mutableStateOf(existingHost?.ipmiEnabled ?: false) }
    var ipmiPort by remember { mutableStateOf((existingHost?.ipmiPort ?: 623).toString()) }

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
        bottomBar = {
            // Kept outside the tabs: the required fields are spread across Général and
            // Authentification, so saving must not depend on which tab happens to be open.
            Surface(tonalElevation = 3.dp) {
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
                            ipmiEnabled = ipmiEnabled,
                            ipmiPort = ipmiPort.toIntOrNull() ?: 623,
                        )
                        repository.saveHost(host)
                        // A session for this host may already be running with the previous record;
                        // without this the edit would only take effect after an app restart.
                        HostSessionStore.updateHost(host)
                        onDone()
                    },
                    enabled = name.isNotBlank() && hostname.isNotBlank() && username.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        // Without these the button sits under the system navigation bar, and under
                        // the keyboard when a field above is being edited.
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(16.dp),
                ) {
                    Text("Enregistrer")
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                TAB_TITLES.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (selectedTab) {
                    0 -> GeneralTab(
                        name = name,
                        onNameChange = { name = it },
                        hostname = hostname,
                        onHostnameChange = { hostname = it },
                        port = port,
                        onPortChange = { port = it },
                        httpsPort = httpsPort,
                        onHttpsPortChange = { httpsPort = it },
                    )
                    1 -> AuthenticationTab(
                        isNewHost = existingHost == null,
                        username = username,
                        onUsernameChange = { username = it },
                        authMethod = authMethod,
                        onAuthMethodChange = { authMethod = it },
                        password = password,
                        onPasswordChange = { password = it },
                        privateKey = privateKey,
                        onPrivateKeyChange = { privateKey = it },
                        passphrase = passphrase,
                        onPassphraseChange = { passphrase = it },
                        publicKey = publicKey,
                        onImportKey = { filePicker.launch("*/*") },
                        onGenerateKey = {
                            scope.launch {
                                val generated = withContext(Dispatchers.Default) {
                                    SshKeyGenerator.generateRsaKeyPair(comment = username.ifBlank { "ilo3manager" })
                                }
                                privateKey = generated.privateKeyPem
                                publicKey = generated.publicKeyOpenSsh
                            }
                        },
                    )
                    2 -> IpmiTab(
                        enabled = ipmiEnabled,
                        onEnabledChange = { ipmiEnabled = it },
                        port = ipmiPort,
                        onPortChange = { ipmiPort = it },
                        authMethod = authMethod,
                        hasStoredPassword = !existingHost?.password.isNullOrBlank(),
                        typedPassword = password,
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneralTab(
    name: String,
    onNameChange: (String) -> Unit,
    hostname: String,
    onHostnameChange: (String) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    httpsPort: String,
    onHttpsPortChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Nom") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = hostname,
        onValueChange = onHostnameChange,
        label = { Text("Adresse (IP ou nom d'hôte)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = port,
        onValueChange = { onPortChange(it.filter { c -> c.isDigit() }) },
        label = { Text("Port SSH") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = httpsPort,
        onValueChange = { onHttpsPortChange(it.filter { c -> c.isDigit() }) },
        label = { Text("Port HTTPS (interface web iLO)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AuthenticationTab(
    isNewHost: Boolean,
    username: String,
    onUsernameChange: (String) -> Unit,
    authMethod: AuthMethod,
    onAuthMethodChange: (AuthMethod) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    privateKey: String,
    onPrivateKeyChange: (String) -> Unit,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    publicKey: String,
    onImportKey: () -> Unit,
    onGenerateKey: () -> Unit,
) {
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text("Utilisateur") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()

    Text("Méthode d'authentification")
    AuthMethod.entries.forEach { method ->
        Row {
            RadioButton(selected = authMethod == method, onClick = { onAuthMethodChange(method) })
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
                onValueChange = onPasswordChange,
                label = {
                    Text(
                        if (isNewHost) "Mot de passe" else "Nouveau mot de passe (laisser vide pour ne pas changer)",
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AuthMethod.PRIVATE_KEY -> {
            Row {
                OutlinedButton(onClick = onImportKey) {
                    Text("Importer un fichier de clé")
                }
                OutlinedButton(
                    onClick = onGenerateKey,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text("Générer une paire de clés")
                }
            }
            Spacer()
            OutlinedTextField(
                value = privateKey,
                onValueChange = onPrivateKeyChange,
                label = {
                    Text(
                        if (isNewHost) {
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
                onValueChange = onPassphraseChange,
                label = {
                    Text(
                        if (isNewHost) {
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
}

@Composable
private fun IpmiTab(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    port: String,
    onPortChange: (String) -> Unit,
    authMethod: AuthMethod,
    hasStoredPassword: Boolean,
    typedPassword: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // weight(1f) is what keeps the description inside the space left by the Switch; without it
        // the column takes its intrinsic width and the text runs underneath the control.
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text("Utiliser IPMI pour l'alimentation")
            Text(
                "L'onglet Alim passe alors entièrement par IPMI : l'état et les actions " +
                    "d'alimentation répondent en une fraction de seconde, sans ouvrir de session SSH.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }

    if (enabled) {
        Spacer()
        OutlinedTextField(
            value = port,
            onValueChange = { onPortChange(it.filter { c -> c.isDigit() }) },
            label = { Text("Port IPMI (UDP)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer()
        Text(
            "IPMI/DCMI par LAN n'est pas actif par défaut sur un iLO3 : activez-le d'abord côté " +
                "iLO (Administration > Access Settings), sinon rien ne répondra sur ce port.",
            style = MaterialTheme.typography.bodySmall,
        )
        // IPMI authenticates with the account password (RAKP), so a key-only host cannot use it.
        if (authMethod == AuthMethod.PRIVATE_KEY && !hasStoredPassword && typedPassword.isBlank()) {
            Spacer()
            Text(
                "Cet hôte est configuré en clé privée sans mot de passe enregistré. IPMI " +
                    "s'authentifie par mot de passe : renseignez-en un dans l'onglet " +
                    "Authentification, sinon IPMI restera inutilisé.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Spacer() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
}
