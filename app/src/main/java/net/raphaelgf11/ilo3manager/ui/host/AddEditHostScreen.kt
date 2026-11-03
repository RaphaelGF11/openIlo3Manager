package net.raphaelgf11.ilo3manager.ui.host

import android.net.Uri
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.data.AuthMethod
import net.raphaelgf11.ilo3manager.R
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
import net.raphaelgf11.ilo3manager.ui.common.detailResource
import net.raphaelgf11.ilo3manager.ui.common.labelResource
import net.raphaelgf11.ilo3manager.ui.common.titleResource
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

private fun tabTitleResource(tab: String): Int = when (tab) {
    TAB_GENERAL -> R.string.host_tab_general
    TAB_AUTH -> R.string.host_tab_auth
    TAB_IPMI -> R.string.host_tab_ipmi
    else -> R.string.host_tab_notifications
}

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
                title = {
                    Text(stringResource(if (existingHost == null) R.string.host_editor_new else R.string.host_editor_edit))
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                    Text(stringResource(R.string.common_save))
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
                        text = { Text(stringResource(tabTitleResource(title))) },
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
        label = { Text(stringResource(R.string.host_name_label)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = hostname,
        onValueChange = onHostnameChange,
        label = { Text(stringResource(R.string.host_address_label)) },
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
        label = { Text(stringResource(R.string.host_ssh_port_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = httpsPort,
        onValueChange = { onHttpsPortChange(it.filter { c -> c.isDigit() }) },
        label = { Text(stringResource(R.string.host_https_port_label)) },
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
            Text(stringResource(R.string.host_vsp_auto_open))
            Text(
                stringResource(R.string.host_vsp_auto_open_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = alwaysOpenVsp, onCheckedChange = onAlwaysOpenVspChange)
    }

    Spacer()
    Text(stringResource(R.string.host_nic_addresses), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.host_nic_addresses_help),
        style = MaterialTheme.typography.bodySmall,
    )
    nicAddresses.forEachIndexed { index, address ->
        OutlinedTextField(
            value = address,
            onValueChange = { nicAddresses[index] = it.trim() },
            label = { Text(stringResource(R.string.host_port_number, index + 1)) },
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
            Text(stringResource(R.string.host_web_gateway_only))
            Text(
                stringResource(R.string.host_web_gateway_only_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = webGatewayOnly, onCheckedChange = onWebGatewayOnlyChange)
    }

    if (!webGatewayOnly) {
        Spacer()
        Text(stringResource(R.string.host_default_tab))
        HostTab.entries.forEach { candidate ->
            RadioRow(
                selected = defaultTab == candidate,
                label = stringResource(candidate.titleResource()),
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
        label = { Text(stringResource(R.string.host_username)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()

    Text(stringResource(R.string.host_auth_method))
    AuthMethod.entries.forEach { method ->
        RadioRow(
            selected = authMethod == method,
            label = when (method) {
                AuthMethod.PASSWORD -> stringResource(R.string.host_password)
                AuthMethod.PRIVATE_KEY -> stringResource(R.string.host_private_key)
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
                        stringResource(
                            if (isNewHost) R.string.host_password else R.string.host_new_password_optional,
                        ),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AuthMethod.PRIVATE_KEY -> {
            Row {
                OutlinedButton(onClick = onImportKey) {
                    Text(stringResource(R.string.host_import_key))
                }
                OutlinedButton(
                    onClick = onGenerateKey,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(stringResource(R.string.host_generate_key))
                }
            }
            Spacer()
            OutlinedTextField(
                value = privateKey,
                onValueChange = onPrivateKeyChange,
                label = {
                    Text(
                        if (isNewHost) {
                            stringResource(R.string.host_private_key_paste)
                        } else {
                            stringResource(R.string.host_private_key_replace)
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
                            stringResource(R.string.host_key_passphrase_optional)
                        } else {
                            stringResource(R.string.host_key_passphrase_replace)
                        },
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (publicKey.isNotBlank()) {
                Spacer()
                Text(stringResource(R.string.host_public_key_ilo))
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
            Text(stringResource(R.string.host_use_ipmi_power))
            Text(
                stringResource(R.string.host_use_ipmi_power_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }

    if (enabled) {
        Spacer()
        Text(stringResource(R.string.host_ipmi_privilege))
        Text(
            stringResource(R.string.host_ipmi_privilege_help),
            style = MaterialTheme.typography.bodySmall,
        )
        IpmiPrivilege.entries.forEach { candidate ->
            RadioRow(
                selected = privilege == candidate,
                label = stringResource(candidate.labelResource()),
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
                Text(stringResource(R.string.host_show_state_list))
                Text(
                    stringResource(R.string.host_show_state_list_help),
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
                Text(stringResource(R.string.host_hardware_ipmi))
                Text(
                    stringResource(R.string.host_hardware_ipmi_help),
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
                Text(stringResource(R.string.host_always_open_ssh))
                Text(
                    stringResource(R.string.host_always_open_ssh_help),
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
                Text(stringResource(R.string.host_monitor_ipmi))
                Text(
                    stringResource(R.string.host_monitor_ipmi_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = notificationsOverIpmi, onCheckedChange = onNotificationsOverIpmiChange)
        }
        Spacer()
        OutlinedTextField(
            value = port,
            onValueChange = { onPortChange(it.filter { c -> c.isDigit() }) },
            label = { Text(stringResource(R.string.host_ipmi_port)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer()
        Text(
            stringResource(R.string.host_ipmi_lan_help),
            style = MaterialTheme.typography.bodySmall,
        )
        // IPMI authenticates with the account password (RAKP), so a key-only host cannot use it.
        if (authMethod == AuthMethod.PRIVATE_KEY && !hasStoredPassword && typedPassword.isBlank()) {
            Spacer()
            Text(
                stringResource(R.string.host_ipmi_password_required),
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
        Text(stringResource(R.string.host_no_network_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.host_no_network_help),
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
                dangling -> stringResource(R.string.host_network_not_found)
                selected != null -> selected.name
                else -> stringResource(R.string.wizard_direct_access)
            },
            onValueChange = {},
            readOnly = true,
            isError = dangling,
            label = { Text(stringResource(R.string.wizard_network)) },
            supportingText = {
                Text(
                    when {
                        dangling -> stringResource(R.string.host_network_missing_help)
                        selected == null -> stringResource(R.string.host_network_direct_help)
                        !selected.isUsable -> "${stringResource(selected.type.labelResource())} — ${stringResource(R.string.host_network_incomplete)}"
                        else -> stringResource(selected.type.labelResource())
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
                text = { Text(stringResource(R.string.wizard_direct_access)) },
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
                                stringResource(network.type.labelResource()) +
                                    if (network.isUsable) "" else " — ${stringResource(R.string.host_network_incomplete)}",
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
            Text(stringResource(R.string.notifications_monitor_host))
            Text(
                stringResource(R.string.notifications_monitor_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = settings.enabled,
            onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) },
        )
    }

    Spacer()
    Text(stringResource(R.string.notifications_interval_title), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.notifications_min_interval_help),
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
    Text(stringResource(R.string.notifications_alert_when), style = MaterialTheme.typography.titleSmall)
    NotifToggle(R.string.notifications_connection_failure, settings.notifyOnConnectionFailure) {
        onSettingsChange(settings.copy(notifyOnConnectionFailure = it))
    }
    NotifToggle(R.string.notifications_critical_state, settings.notifyOnCritical) {
        onSettingsChange(settings.copy(notifyOnCritical = it))
    }
    NotifToggle(R.string.notifications_degraded_state, settings.notifyOnDegraded) {
        onSettingsChange(settings.copy(notifyOnDegraded = it))
    }
    NotifToggle(R.string.notifications_server_powered_off, settings.notifyOnPoweredOff) {
        onSettingsChange(settings.copy(notifyOnPoweredOff = it))
    }
    NotifToggle(R.string.notifications_server_powered_on, settings.notifyOnPoweredOn) {
        onSettingsChange(settings.copy(notifyOnPoweredOn = it))
    }

    Spacer()
    Text(
        stringResource(
            R.string.notifications_last_check,
            if (lastChecked > 0) java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT,
                java.text.DateFormat.SHORT,
            ).format(java.util.Date(lastChecked)) else stringResource(R.string.notifications_never),
        ),
        style = MaterialTheme.typography.bodySmall,
    )

    if (hostId == null) {
        Text(
            stringResource(R.string.notifications_save_before_test),
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
            Text(stringResource(if (testing) R.string.notifications_checking else R.string.notifications_test_now))
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
    Text(stringResource(R.string.instant_alert_title), style = MaterialTheme.typography.titleSmall)
    InstantAlertMode.entries.forEach { candidate ->
        RadioRow(
            selected = mode == candidate,
            label = stringResource(candidate.labelResource()),
            detail = stringResource(candidate.detailResource()),
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
                label = { Text(stringResource(R.string.instant_alert_gateway_address)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer()
            Text(
                stringResource(R.string.instant_alert_not_implemented),
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
    val context = LocalContext.current
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
            onSuccess = { context.getString(R.string.instant_alert_destination, it) },
            onFailure = { context.getString(R.string.instant_alert_unknown_destination, it.message.orEmpty()) },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (destination.isFailure) MaterialTheme.colorScheme.error else Color.Unspecified,
    )

    if (savedHost == null) {
        Spacer()
        Text(
            stringResource(R.string.instant_alert_save_first),
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
            Text(stringResource(R.string.instant_alert_configure))
        }
        OutlinedButton(
            onClick = { run { SnmpDirectActions.testReachability(savedHost, network) } },
            enabled = !busy && destination.isSuccess,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.instant_alert_test))
        }
    }

    Spacer()
    if (busy) {
        Text(stringResource(R.string.instant_alert_ssh_session), style = MaterialTheme.typography.bodySmall)
    }
    outcome?.let { result ->
        Text(
            result.fold(
                onSuccess = { it },
                onFailure = { it.message ?: context.getString(R.string.instant_alert_failed_no_message) },
            ),
            color = if (result.isFailure) MaterialTheme.colorScheme.error else Color.Unspecified,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer()
    Text(
        stringResource(R.string.instant_alert_test_explanation),
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer()
    Text(
        stringResource(R.string.instant_alert_listener_explanation),
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
        title = { Text(stringResource(R.string.instant_alert_choose_destination)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.instant_alert_slot_explanation, state.destination),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer()
                state.slots.forEachIndexed { index, value ->
                    val slot = index + 1
                    val free = value.isBlank() || value == "0" || value == "0.0.0.0"
                    RadioRow(
                        selected = selected == slot,
                        label = stringResource(R.string.instant_alert_destination_slot, slot),
                        detail = when {
                            value.trim() == state.destination -> stringResource(R.string.instant_alert_already_this_phone)
                            free -> stringResource(R.string.instant_alert_slot_free)
                            else -> stringResource(R.string.instant_alert_slot_replace, value)
                        },
                        onSelect = { selected = slot },
                    )
                }
                if (!state.alertsEnabled) {
                    Spacer()
                    Text(
                        stringResource(R.string.instant_alert_enable_ilo_alerts),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.instant_alert_write)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun NotifToggle(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(selected = checked, onClick = { onChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(stringResource(label), modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
