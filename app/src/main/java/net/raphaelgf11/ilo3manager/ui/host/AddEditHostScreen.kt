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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.data.AuthMethod
import net.raphaelgf11.ilo3manager.data.HostNotificationSettings
import net.raphaelgf11.ilo3manager.notify.MonitorScheduler
import net.raphaelgf11.ilo3manager.notify.SnmpDirectActions
import net.raphaelgf11.ilo3manager.notify.trapDestinationBlocker
import net.raphaelgf11.ilo3manager.notify.trapDestinationFor
import net.raphaelgf11.ilo3manager.notify.MonitorStateStore
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.HostTab
import net.raphaelgf11.ilo3manager.data.InstantAlertMode
import net.raphaelgf11.ilo3manager.data.NetworkConfig
import net.raphaelgf11.ilo3manager.data.NetworkRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ipmi.IpmiPrivilege
import net.raphaelgf11.ilo3manager.ssh.HostSessionStore
import net.raphaelgf11.ilo3manager.ssh.SshKeyGenerator
import net.raphaelgf11.ilo3manager.ui.common.RadioRow
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAB_GENERAL = "Général"
private const val TAB_AUTH = "Authentification"
private const val TAB_IPMI = "IPMI"
private const val TAB_NOTIF = "Notifications"

/** Credentials and IPMI are meaningless for a gateway-only host, so those tabs disappear. */
private fun tabsFor(webGatewayOnly: Boolean): List<String> =
    if (webGatewayOnly) listOf(TAB_GENERAL)
    else listOf(TAB_GENERAL, TAB_AUTH, TAB_IPMI, TAB_NOTIF)

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

    val notificationRepository = remember { net.raphaelgf11.ilo3manager.data.NotificationSettingsRepository(context) }
    var notifSettings by remember {
        mutableStateOf(existingHost?.let { notificationRepository.settingsFor(it.id) }
            ?: net.raphaelgf11.ilo3manager.data.HostNotificationSettings())
    }

    var name by remember { mutableStateOf(existingHost?.name ?: "") }
    var hostname by remember { mutableStateOf(existingHost?.hostname ?: "") }
    var port by remember { mutableStateOf((existingHost?.port ?: 22).toString()) }
    var httpsPort by remember { mutableStateOf((existingHost?.httpsPort ?: 443).toString()) }
    var alwaysOpenVsp by remember { mutableStateOf(existingHost?.alwaysOpenVsp ?: false) }
    val nicAddresses = remember {
        mutableStateListOf<String>().apply {
            addAll(existingHost?.nicAddresses ?: List(4) { "" })
            while (size < 4) add("")
        }
    }
    var defaultTab by remember { mutableStateOf(existingHost?.defaultTab ?: HostTab.POWER) }
    var webGatewayOnly by remember { mutableStateOf(existingHost?.webGatewayOnly ?: false) }
    val tabs = tabsFor(webGatewayOnly)
    // Switching the mode shortens the tab list; without this the selection could point past its end.
    if (selectedTab >= tabs.size) selectedTab = 0
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
    var showStateInList by remember { mutableStateOf(existingHost?.showStateInList ?: false) }
    var hardwareOverIpmi by remember { mutableStateOf(existingHost?.hardwareOverIpmi ?: false) }
    var ipmiPrivilege by remember { mutableStateOf(existingHost?.ipmiPrivilege ?: IpmiPrivilege.OPERATOR) }
    var alwaysOpenSsh by remember { mutableStateOf(existingHost?.alwaysOpenSsh ?: false) }
    var notificationsOverIpmi by remember { mutableStateOf(existingHost?.notificationsOverIpmi ?: false) }
    var networkId by remember { mutableStateOf(existingHost?.networkId ?: "") }
    var instantAlertMode by remember {
        mutableStateOf(existingHost?.instantAlertMode ?: InstantAlertMode.DISABLED)
    }
    var alertGatewayUrl by remember { mutableStateOf(existingHost?.alertGatewayUrl ?: "") }

    // Read once per visit to the screen: networks are managed on their own screen, which cannot be
    // reached from here without leaving it first.
    val networks = remember { NetworkRepository(context).getNetworks() }

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
                            alwaysOpenVsp = alwaysOpenVsp,
                            nicAddresses = nicAddresses.toList(),
                            defaultTab = if (webGatewayOnly) HostTab.WEB else defaultTab,
                            webGatewayOnly = webGatewayOnly,
                            username = username,
                            authMethod = authMethod,
                            password = password.ifBlank { existingHost?.password ?: "" },
                            privateKey = if (keyChanged) privateKey else existingHost?.privateKey ?: "",
                            privateKeyPassphrase = if (keyChanged) passphrase else existingHost?.privateKeyPassphrase ?: "",
                            publicKey = if (keyChanged) publicKey else existingHost?.publicKey ?: "",
                            ipmiEnabled = ipmiEnabled,
                            ipmiPort = ipmiPort.toIntOrNull() ?: 623,
                            ipmiPromptDismissed = existingHost?.ipmiPromptDismissed ?: false,
                            showStateInList = showStateInList && ipmiEnabled,
                            hardwareOverIpmi = hardwareOverIpmi && ipmiEnabled,
                            ipmiPrivilege = ipmiPrivilege,
                            alwaysOpenSsh = alwaysOpenSsh && ipmiEnabled,
                            notificationsOverIpmi = notificationsOverIpmi && ipmiEnabled,
                            networkId = networkId,
                            instantAlertMode = instantAlertMode,
                            alertGatewayUrl = alertGatewayUrl,
                        )
                        repository.saveHost(host)
                        notificationRepository.save(host.id, notifSettings)
                        // Rescheduling here rather than only at app start: enabling monitoring on a
                        // host must take effect now, not at the next launch.
                        net.raphaelgf11.ilo3manager.notify.MonitorScheduler.reschedule(context, notificationRepository)
                        // Switching instant alerts on or off has to take effect now, and switching
                        // them off has to actually release the socket.
                        net.raphaelgf11.ilo3manager.notify.TrapReceiverService.sync(context)
                        // A session for this host may already be running with the previous record;
                        // without this the edit would only take effect after an app restart.
                        HostSessionStore.updateHost(host)
                        onDone()
                    },
                    // A gateway-only host authenticates in the browser, so it stores no account.
                    enabled = name.isNotBlank() && hostname.isNotBlank() &&
                        (webGatewayOnly || username.isNotBlank()),
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
                tabs.forEachIndexed { index, title ->
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
                when (tabs.getOrNull(selectedTab)) {
                    TAB_GENERAL -> GeneralTab(
                        name = name,
                        onNameChange = { name = it },
                        hostname = hostname,
                        onHostnameChange = { hostname = it },
                        port = port,
                        onPortChange = { port = it },
                        httpsPort = httpsPort,
                        onHttpsPortChange = { httpsPort = it },
                        alwaysOpenVsp = alwaysOpenVsp,
                        onAlwaysOpenVspChange = { alwaysOpenVsp = it },
                        nicAddresses = nicAddresses,
                        defaultTab = defaultTab,
                        onDefaultTabChange = { defaultTab = it },
                        webGatewayOnly = webGatewayOnly,
                        onWebGatewayOnlyChange = { webGatewayOnly = it },
                        networks = networks,
                        networkId = networkId,
                        onNetworkIdChange = { networkId = it },
                    )
                    TAB_AUTH -> AuthenticationTab(
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
                    TAB_NOTIF -> NotificationsTab(
                        hostId = existingHost?.id,
                        settings = notifSettings,
                        onSettingsChange = { notifSettings = it },
                        instantAlertMode = instantAlertMode,
                        onInstantAlertModeChange = { instantAlertMode = it },
                        alertGatewayUrl = alertGatewayUrl,
                        onAlertGatewayUrlChange = { alertGatewayUrl = it },
                        // The network currently picked on the Général tab, not the saved one: the
                        // trap destination has to match the choice the user is looking at.
                        network = networks.firstOrNull { it.id == networkId },
                        savedHost = existingHost?.copy(networkId = networkId),
                    )
                    TAB_IPMI -> IpmiTab(
                        enabled = ipmiEnabled,
                        onEnabledChange = { ipmiEnabled = it },
                        showStateInList = showStateInList,
                        onShowStateInListChange = { showStateInList = it },
                        hardwareOverIpmi = hardwareOverIpmi,
                        onHardwareOverIpmiChange = { hardwareOverIpmi = it },
                        privilege = ipmiPrivilege,
                        onPrivilegeChange = { ipmiPrivilege = it },
                        alwaysOpenSsh = alwaysOpenSsh,
                        onAlwaysOpenSshChange = { alwaysOpenSsh = it },
                        notificationsOverIpmi = notificationsOverIpmi,
                        onNotificationsOverIpmiChange = { notificationsOverIpmi = it },
                        port = ipmiPort,
                        onPortChange = { ipmiPort = it },
                        authMethod = authMethod,
                        hasStoredPassword = !existingHost?.password.isNullOrBlank(),
                        typedPassword = password,
                    )
                    // Unreachable: the selection is always an index into the list built above.
                    else -> Unit
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
    alwaysOpenVsp: Boolean,
    nicAddresses: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    onAlwaysOpenVspChange: (Boolean) -> Unit,
    defaultTab: HostTab,
    onDefaultTabChange: (HostTab) -> Unit,
    webGatewayOnly: Boolean,
    onWebGatewayOnlyChange: (Boolean) -> Unit,
    networks: List<NetworkConfig>,
    networkId: String,
    onNetworkIdChange: (String) -> Unit,
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
    NetworkPicker(
        networks = networks,
        networkId = networkId,
        onNetworkIdChange = onNetworkIdChange,
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
    Spacer()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Ouvrir le port série (VSP) à l'ouverture")
            Text(
                "La console série utilise sa propre session SSH, distincte de celle des autres " +
                    "onglets : l'ouvrir d'avance rend l'onglet VSP immédiatement utilisable. " +
                    "Elle consomme en contrepartie une des rares sessions simultanées de l'iLO.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = alwaysOpenVsp, onCheckedChange = onAlwaysOpenVspChange)
    }

    Spacer()
    Text("Adresses des cartes réseau", style = MaterialTheme.typography.titleSmall)
    Text(
        "Une adresse par port, dans l'ordre du panneau avant. Les voyants réseau s'allument quand " +
            "l'adresse répond à un ping. C'est une approximation : ils indiquent en réalité l'état " +
            "du lien, que ni l'IPMI ni l'API de l'iLO ne rapportent — un port câblé mais sans " +
            "adresse restera donc éteint ici. Laissez vide les ports que vous ne suivez pas.",
        style = MaterialTheme.typography.bodySmall,
    )
    nicAddresses.forEachIndexed { index, address ->
        OutlinedTextField(
            value = address,
            onValueChange = { nicAddresses[index] = it.trim() },
            label = { Text("Port ${index + 1}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Passerelle web uniquement")
            Text(
                "Le serveur n'expose que l'interface web de l'iLO et ne demande aucun identifiant : " +
                    "l'authentification a lieu dans le navigateur, sur la page de connexion de " +
                    "l'iLO. Utile pour atteindre un iLO dont aucun navigateur moderne ne veut, " +
                    "justement pour y créer le compte dédié ou y déposer une clé.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = webGatewayOnly, onCheckedChange = onWebGatewayOnlyChange)
    }

    if (!webGatewayOnly) {
        Spacer()
        Text("Onglet affiché à l'ouverture")
        HostTab.entries.forEach { candidate ->
            RadioRow(
                selected = defaultTab == candidate,
                label = candidate.title,
                onSelect = { onDefaultTabChange(candidate) },
            )
        }
    }
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
        RadioRow(
            selected = authMethod == method,
            label = when (method) {
                AuthMethod.PASSWORD -> "Mot de passe"
                AuthMethod.PRIVATE_KEY -> "Clé privée"
            },
            onSelect = { onAuthMethodChange(method) },
        )
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
    showStateInList: Boolean,
    onShowStateInListChange: (Boolean) -> Unit,
    hardwareOverIpmi: Boolean,
    onHardwareOverIpmiChange: (Boolean) -> Unit,
    privilege: IpmiPrivilege,
    onPrivilegeChange: (IpmiPrivilege) -> Unit,
    alwaysOpenSsh: Boolean,
    notificationsOverIpmi: Boolean,
    onNotificationsOverIpmiChange: (Boolean) -> Unit,
    onAlwaysOpenSshChange: (Boolean) -> Unit,
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
        Text("Niveau de privilège demandé")
        Text(
            "Opérateur suffit pour l'alimentation et la LED UID ; Lecture seule permet de " +
                "consulter l'état sans rien modifier. Attention : l'iLO plafonne le niveau " +
                "accordé selon les privilèges du compte — un compte incomplet est ramené en " +
                "lecture seule, quel que soit le niveau demandé ici.",
            style = MaterialTheme.typography.bodySmall,
        )
        IpmiPrivilege.entries.forEach { candidate ->
            RadioRow(
                selected = privilege == candidate,
                label = candidate.label,
                onSelect = { onPrivilegeChange(candidate) },
            )
        }

        Spacer()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Afficher l'état dans la liste")
                Text(
                    "La liste des serveurs interroge alors cet hôte périodiquement pour y afficher " +
                        "son alimentation et ses défauts. Réservé à IPMI : une connexion SSH par " +
                        "hôte serait bien trop lente et saturerait les sessions de l'iLO.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = showStateInList, onCheckedChange = onShowStateInListChange)
        }
        Spacer()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Onglet HW via IPMI")
                Text(
                    "L'onglet Matériel lit alors les capteurs IPMI au lieu de parcourir l'arborescence " +
                        "CLI : quelques secondes au lieu d'une quinzaine, et aucune session SSH. " +
                        "En contrepartie il affiche des capteurs (températures, ventilateurs, " +
                        "alimentations) et non l'inventaire : ni détail par barrette mémoire ou par " +
                        "processeur, ni numéros de série.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = hardwareOverIpmi, onCheckedChange = onHardwareOverIpmiChange)
        }
        Spacer()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Toujours ouvrir la session SSH")
                Text(
                    "Une fois l'onglet Alim affiché par IPMI, la session SSH s'ouvre en arrière-plan " +
                        "au lieu d'attendre qu'un onglet en ait besoin : les onglets VSP, SSH et " +
                        "Matériel sont alors immédiatement utilisables. Utile si IPMI est limité à " +
                        "la lecture, ou si vous vous servez souvent de ces onglets. " +
                        "Uniquement à l'ouverture d'un serveur — jamais depuis la liste.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = alwaysOpenSsh, onCheckedChange = onAlwaysOpenSshChange)
        }

        Spacer()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text("Surveillance par IPMI")
                Text(
                    "La vérification périodique ouvre une connexion neuve à chaque tour, pour " +
                        "chaque serveur surveillé : en SSH cela coûte plusieurs secondes et l'une " +
                        "des rares sessions simultanées de l'iLO, en IPMI quelques échanges UDP. " +
                        "En contrepartie l'alerte nomme des capteurs plutôt que des composants.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = notificationsOverIpmi, onCheckedChange = onNotificationsOverIpmiChange)
        }
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

/**
 * Chooses which named network this host is reached through.
 *
 * The configuration itself lives on the Réseaux screen, not here: it is shared between hosts, so
 * editing it from inside one server would hide that changing it changes the others too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkPicker(
    networks: List<NetworkConfig>,
    networkId: String,
    onNetworkIdChange: (String) -> Unit,
) {
    if (networks.isEmpty()) {
        Text("Réseau", style = MaterialTheme.typography.titleSmall)
        Text(
            "Aucun réseau défini. Ce serveur est joint directement. Pour passer par un tunnel, " +
                "créez un réseau avec le bouton Réseaux de l'écran d'accueil.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    val selected = networks.firstOrNull { it.id == networkId }
    // A host can point at a network a restore did not bring back. Saying so in the field beats
    // showing "Accès direct", which would be a plain lie about how this host connects.
    val dangling = networkId.isNotBlank() && selected == null
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = when {
                dangling -> "Réseau introuvable"
                selected != null -> selected.name
                else -> DIRECT_ACCESS
            },
            onValueChange = {},
            readOnly = true,
            isError = dangling,
            label = { Text("Réseau") },
            supportingText = {
                Text(
                    when {
                        dangling -> "Le réseau enregistré pour ce serveur n'existe plus. " +
                            "Choisissez-en un autre, ou l'accès direct."
                        selected == null -> "Le serveur est joint sans tunnel."
                        !selected.isUsable -> "${selected.type.label} — configuration incomplète"
                        else -> selected.type.label
                    },
                )
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            // Anchors the menu to the field and gives it the same width; without it the menu
            // appears at the top of the screen.
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(DIRECT_ACCESS) },
                onClick = {
                    onNetworkIdChange("")
                    expanded = false
                },
            )
            networks.forEach { network ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(network.name)
                            Text(
                                network.type.label +
                                    if (network.isUsable) "" else " — configuration incomplète",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    onClick = {
                        onNetworkIdChange(network.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val DIRECT_ACCESS = "Accès direct"

@Composable
private fun Spacer() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
}

/**
 * Per-host monitoring, moved out of the list's bell dialog.
 *
 * The test button exists because the periodic check only reports *transitions*: waiting for it
 * proves nothing about whether the chain works, since a healthy server that was already healthy is
 * meant to stay silent. Forcing a check bypasses both the interval and the enabled flag, so the
 * setup can be tried before being trusted.
 */
@Composable
private fun NotificationsTab(
    hostId: String?,
    settings: HostNotificationSettings,
    onSettingsChange: (HostNotificationSettings) -> Unit,
    instantAlertMode: InstantAlertMode,
    onInstantAlertModeChange: (InstantAlertMode) -> Unit,
    alertGatewayUrl: String,
    onAlertGatewayUrlChange: (String) -> Unit,
    network: NetworkConfig?,
    savedHost: SshHost?,
) {
    val context = LocalContext.current
    val stateStore = remember { MonitorStateStore(context) }
    var lastChecked by remember { mutableStateOf(hostId?.let { stateStore.get(it).lastCheckedAtMillis } ?: 0L) }
    var testing by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text("Surveiller ce serveur")
            Text(
                "Une vérification périodique en arrière-plan, qui n'alerte que lorsque l'état " +
                    "change : un serveur sain qui le reste ne produit aucune notification.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) },
        )
    }

    Spacer()
    Text("Intervalle", style = MaterialTheme.typography.titleSmall)
    Text(
        "Le système impose un plancher de 15 minutes aux tâches périodiques ; une valeur plus " +
            "courte ne sera pas respectée.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 60, 180).forEach { minutes ->
            FilterChip(
                selected = settings.intervalMinutes == minutes,
                onClick = { onSettingsChange(settings.copy(intervalMinutes = minutes)) },
                label = { Text(if (minutes < 60) "${minutes}min" else "${minutes / 60}h") },
            )
        }
    }

    Spacer()
    Text("M'alerter en cas de", style = MaterialTheme.typography.titleSmall)
    NotifToggle("Échec de connexion", settings.notifyOnConnectionFailure) {
        onSettingsChange(settings.copy(notifyOnConnectionFailure = it))
    }
    NotifToggle("État critique", settings.notifyOnCritical) {
        onSettingsChange(settings.copy(notifyOnCritical = it))
    }
    NotifToggle("État dégradé", settings.notifyOnDegraded) {
        onSettingsChange(settings.copy(notifyOnDegraded = it))
    }
    NotifToggle("Serveur éteint", settings.notifyOnPoweredOff) {
        onSettingsChange(settings.copy(notifyOnPoweredOff = it))
    }
    NotifToggle("Serveur allumé", settings.notifyOnPoweredOn) {
        onSettingsChange(settings.copy(notifyOnPoweredOn = it))
    }

    Spacer()
    Text(
        "Dernière vérification : " + if (lastChecked > 0) {
            java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT,
                java.text.DateFormat.SHORT,
            ).format(java.util.Date(lastChecked))
        } else {
            "jamais"
        },
        style = MaterialTheme.typography.bodySmall,
    )

    if (hostId == null) {
        Text(
            "Enregistrez le serveur avant de pouvoir tester.",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        OutlinedButton(
            onClick = {
                testing = true
                MonitorScheduler.runNow(context, hostId)
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (testing) "Vérification en cours…" else "Tester maintenant")
        }
    }

    // The worker writes the timestamp when it finishes; polling briefly is enough to pick it up
    // without wiring an observer through WorkManager for a button pressed once in a while.
    LaunchedEffect(testing) {
        if (!testing || hostId == null) return@LaunchedEffect
        repeat(30) {
            kotlinx.coroutines.delay(1_000)
            val stamp = stateStore.get(hostId).lastCheckedAtMillis
            if (stamp != lastChecked) {
                lastChecked = stamp
                testing = false
                return@LaunchedEffect
            }
        }
        testing = false
    }

    Spacer()
    InstantAlertSection(
        mode = instantAlertMode,
        onModeChange = onInstantAlertModeChange,
        alertGatewayUrl = alertGatewayUrl,
        onAlertGatewayUrlChange = onAlertGatewayUrlChange,
        network = network,
        savedHost = savedHost,
    )
}

/**
 * Alerting between two periodic checks.
 *
 * The periodic check is the safety net and stays whatever is chosen here: a trap is UDP and
 * fire-and-forget, and none arrives at all when the iLO itself is gone.
 */
@Composable
private fun InstantAlertSection(
    mode: InstantAlertMode,
    onModeChange: (InstantAlertMode) -> Unit,
    alertGatewayUrl: String,
    onAlertGatewayUrlChange: (String) -> Unit,
    network: NetworkConfig?,
    savedHost: SshHost?,
) {
    Text("Alerte instantanée", style = MaterialTheme.typography.titleSmall)
    InstantAlertMode.entries.forEach { candidate ->
        RadioRow(
            selected = mode == candidate,
            label = candidate.label,
            detail = candidate.detail,
            onSelect = { onModeChange(candidate) },
        )
    }

    when (mode) {
        InstantAlertMode.SNMP_DIRECT -> SnmpDirectPanel(network = network, savedHost = savedHost)
        InstantAlertMode.SNMP_GATEWAY, InstantAlertMode.FIREBASE -> {
            Spacer()
            OutlinedTextField(
                value = alertGatewayUrl,
                onValueChange = onAlertGatewayUrlChange,
                label = { Text("Adresse de la passerelle (hôte:port)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer()
            Text(
                "Le transport n'est pas encore implémenté : ce réglage est enregistré mais " +
                    "aucune alerte ne transitera par cette passerelle pour l'instant.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        InstantAlertMode.DISABLED -> Unit
    }
}

/**
 * Configuring the iLO to send its traps here, and checking it can.
 *
 * The two buttons answer different questions and neither replaces the other: configuring writes a
 * destination into the BMC, testing asks the BMC whether that destination is reachable from where
 * it stands.
 */
@Composable
private fun SnmpDirectPanel(network: NetworkConfig?, savedHost: SshHost?) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Result<String>?>(null) }

    var slotChoice by remember { mutableStateOf<SnmpDirectActions.SnmpState?>(null) }

    val blocker = remember(network) { trapDestinationBlocker(network) }
    val destination = remember(network) {
        trapDestinationFor(network, SnmpDirectActions::localIpv4Addresses)
    }

    Spacer()
    if (blocker != null) {
        Text(blocker, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        return
    }

    Text(
        destination.fold(
            onSuccess = { "L'iLO enverra ses traps à $it." },
            onFailure = { "Destination indéterminable : ${it.message}" },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (destination.isFailure) MaterialTheme.colorScheme.error else Color.Unspecified,
    )

    if (savedHost == null) {
        Spacer()
        Text(
            "Enregistrez le serveur avant de configurer l'iLO : les deux boutons ouvrent une " +
                "session SSH avec ses identifiants.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    fun run(action: suspend () -> Result<String>) {
        busy = true
        outcome = null
        scope.launch {
            outcome = action()
            busy = false
        }
    }

    Spacer()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                busy = true
                outcome = null
                scope.launch {
                    // Read first and let the user pick: writing into a slot the app chose alone
                    // could silently replace a destination their monitoring depends on.
                    SnmpDirectActions.readSnmpState(savedHost, network)
                        .onSuccess { slotChoice = it }
                        .onFailure { outcome = Result.failure(it) }
                    busy = false
                }
            },
            enabled = !busy && destination.isSuccess,
            modifier = Modifier.weight(1f),
        ) {
            Text("Configurer")
        }
        OutlinedButton(
            onClick = { run { SnmpDirectActions.testReachability(savedHost, network) } },
            enabled = !busy && destination.isSuccess,
            modifier = Modifier.weight(1f),
        ) {
            Text("Tester")
        }
    }

    Spacer()
    if (busy) {
        Text("Session SSH en cours…", style = MaterialTheme.typography.bodySmall)
    }
    outcome?.let { result ->
        Text(
            result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: "Échec sans message." },
            ),
            color = if (result.isFailure) MaterialTheme.colorScheme.error else Color.Unspecified,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer()
    Text(
        "« Tester » demande à l'iLO de pinguer le téléphone : c'est le sens qui casse, et le seul " +
            "que ce micrologiciel permette de vérifier — sa CLI n'expose pas le « Send test " +
            "alert » de l'interface web. Un ping qui passe ne garantit pas que le port UDP 162 " +
            "soit ouvert tout du long.",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer()
    Text(
        "Le téléphone écoute les traps dès que ce mode est enregistré, par un service en " +
            "arrière-plan permanent. L'écoute passe obligatoirement par un réseau WireGuard : " +
            "le port 162 est réservé par Android, et seul le tunnel peut l'ouvrir — sa pile " +
            "réseau étant en espace utilisateur, la notion de port privilégié n'y existe pas.",
        style = MaterialTheme.typography.bodySmall,
    )

    slotChoice?.let { state ->
        SnmpSlotDialog(
            state = state,
            onDismiss = { slotChoice = null },
            onConfirm = { slot ->
                slotChoice = null
                run { SnmpDirectActions.writeDestination(savedHost, network, slot) }
            },
        )
    }
}

/**
 * Picks which of the iLO's three destinations to write into.
 *
 * Shows what each currently holds rather than only offering the free ones: replacing a stale
 * destination is a legitimate thing to want, and the user is the only one who can tell a stale one
 * from a monitoring system still in use.
 */
@Composable
private fun SnmpSlotDialog(
    state: SnmpDirectActions.SnmpState,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selected by remember { mutableIntStateOf(state.recommended ?: 1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quelle destination écrire ?") },
        text = {
            Column {
                Text(
                    "L'iLO en garde trois. ${state.destination} sera écrite dans celle que vous " +
                        "choisissez.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer()
                state.slots.forEachIndexed { index, value ->
                    val slot = index + 1
                    val free = value.isBlank() || value == "0" || value == "0.0.0.0"
                    RadioRow(
                        selected = selected == slot,
                        label = "Destination $slot",
                        detail = when {
                            value.trim() == state.destination -> "déjà ce téléphone"
                            free -> "libre"
                            else -> "occupée par $value — sera remplacée"
                        },
                        onSelect = { selected = slot },
                    )
                }
                if (!state.alertsEnabled) {
                    Spacer()
                    Text(
                        "Les alertes iLO sont désactivées ; elles seront activées, sans quoi " +
                            "aucune trap ne partirait.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Écrire") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun NotifToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = checked, onClick = { onChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
