package net.raphaelgf11.ilo3manager.ui.setup

import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.raphaelgf11.ilo3manager.data.AuthMethod
import net.raphaelgf11.ilo3manager.R
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.NetworkConfig
import net.raphaelgf11.ilo3manager.data.NetworkRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ipmi.IpmiPrivilege
import net.raphaelgf11.ilo3manager.setup.HostSetupRunner
import net.raphaelgf11.ilo3manager.setup.IloFirmware
import net.raphaelgf11.ilo3manager.setup.TESTED_ILO_VERSION
import net.raphaelgf11.ilo3manager.setup.isOlderThanTested
import net.raphaelgf11.ilo3manager.setup.IPMI_MAX_PASSWORD_LENGTH
import net.raphaelgf11.ilo3manager.setup.IPMI_MAX_USERNAME_LENGTH
import net.raphaelgf11.ilo3manager.setup.dedicatedAccountDisplayName
import net.raphaelgf11.ilo3manager.setup.dedicatedAccountLosesIpmi
import net.raphaelgf11.ilo3manager.setup.defaultDedicatedUsername
import net.raphaelgf11.ilo3manager.setup.nextFreeDedicatedUsername
import net.raphaelgf11.ilo3manager.ui.common.detailResource
import net.raphaelgf11.ilo3manager.ui.common.labelResource

private const val STEP_IDENTITY = 0
private const val STEP_SSH_PORT = 1
private const val STEP_CREDENTIALS = 2
private const val STEP_ACCOUNT = 3
private const val STEP_IPMI = 4
private const val STEP_DONE = 5

private val STEP_TITLES = listOf(
    R.string.wizard_step_network,
    R.string.wizard_step_ssh_port,
    R.string.wizard_step_credentials,
    R.string.wizard_step_account,
    R.string.wizard_step_ipmi,
    R.string.wizard_step_summary,
)

/**
 * Adds a server by asking the iLO rather than the user.
 *
 * Every value the machine can answer for itself — its HTTPS port, its own name, whether IPMI is on,
 * how far an account's privileges reach — is read instead of typed, because those are exactly the
 * fields a user gets wrong once and then spends an evening not finding.
 *
 * The steps are ordered so nothing is asked before the answer is useful: the credentials are proven
 * first, which also brings back the accounts already on the iLO, so a dedicated one can be offered
 * under the name it will really be given rather than a guess corrected afterwards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSetupWizardScreen(
    hostRepository: HostRepository,
    onDone: () -> Unit,
    onBack: () -> Unit,
    onManualSetup: () -> Unit,
) {
    val context = LocalContext.current
    val networks = remember { NetworkRepository(context).getNetworks() }
    val scope = rememberCoroutineScope()

    var step by remember { mutableIntStateOf(STEP_IDENTITY) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var hostname by remember { mutableStateOf("") }
    var networkId by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var portReachable by remember { mutableStateOf<Boolean?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inspection by remember { mutableStateOf<HostSetupRunner.IloInspection?>(null) }
    var createDedicated by remember { mutableStateOf(true) }
    var dedicatedUsername by remember { mutableStateOf("") }
    // Once the user has touched the field it is theirs; recomposing a suggestion over what they
    // typed would undo their edit as they moved between steps.
    var dedicatedEdited by remember { mutableStateOf(false) }
    var createdAccount by remember { mutableStateOf<HostSetupRunner.CreatedAccount?>(null) }
    var ipmiOutcome by remember { mutableStateOf<HostSetupRunner.IpmiOutcome?>(null) }
    var serverName by remember { mutableStateOf("") }

    // Bumped by the retry buttons: a probe that failed leaves its result null, so without a
    // changing key the effect would not run a second time.
    var scanAttempt by remember { mutableIntStateOf(0) }
    var ipmiAttempt by remember { mutableIntStateOf(0) }

    var saved by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf(false) }
    var cancelError by remember { mutableStateOf<String?>(null) }

    val network = networks.firstOrNull { it.id == networkId }

    /** The record as it stands, which is also what the probes connect through. */
    fun draft(): SshHost = SshHost(
        name = serverName.ifBlank { hostname },
        hostname = hostname,
        port = sshPort.toIntOrNull() ?: 22,
        httpsPort = inspection?.accessConfig?.httpsPort ?: 443,
        networkId = networkId,
        // Once a dedicated account exists, everything past its creation uses it: that is the
        // account the host will be saved with, so it is the one that has to be proven to work.
        username = createdAccount?.username ?: username,
        password = createdAccount?.password ?: password,
        authMethod = AuthMethod.PASSWORD,
        ipmiEnabled = ipmiOutcome is HostSetupRunner.IpmiOutcome.Available,
        ipmiPrivilege = (ipmiOutcome as? HostSetupRunner.IpmiOutcome.Available)?.privilege
            ?: IpmiPrivilege.OPERATOR,
    )

    /** The same server, reached with the credentials the user typed rather than the created ones. */
    fun adminDraft(): SshHost = draft().copy(username = username, password = password)

    /**
     * Leaves the assistant, taking back the account it created along the way.
     *
     * Abandoning half-way would otherwise leave an account on the BMC that nothing uses and nobody
     * remembers authorising — with full privileges and a password only the discarded run ever knew.
     */
    fun leave() {
        val created = createdAccount?.username.orEmpty()
        if (saved || created.isBlank()) {
            onBack()
            return
        }
        cancelling = true
        cancelError = null
        scope.launch {
            HostSetupRunner.deleteAccount(adminDraft(), created)
                .onSuccess {
                    cancelling = false
                    onBack()
                }
                .onFailure {
                    cancelling = false
                    cancelError = it.message ?: context.getString(R.string.wizard_account_remove_failed)
                }
        }
    }

    // The system gesture has to go through the same cleanup as the arrow, or backing out with a
    // swipe would be the one way to strand an account.
    BackHandler(enabled = !cancelling) { leave() }

    /** Runs whatever a step owes before the next one can be shown. */
    fun advance() {
        error = null
        when (step) {
            STEP_CREDENTIALS -> {
                // Proving the credentials here is what lets the next step propose a real name: the
                // same connection brings back the accounts already on the iLO.
                busy = true
                scope.launch {
                    HostSetupRunner.inspect(draft())
                        .onSuccess {
                            inspection = it
                            serverName = serverName.ifBlank { it.serverName }
                            step = STEP_ACCOUNT
                        }
                        .onFailure { error = it.message }
                    busy = false
                }
            }
            STEP_ACCOUNT -> {
                if (!createDedicated || createdAccount != null) {
                    step = STEP_IPMI
                    return
                }
                busy = true
                scope.launch {
                    HostSetupRunner.createDedicatedAccount(
                        admin = adminDraft(),
                        desiredUsername = dedicatedUsername,
                        minPasswordLength = inspection?.accessConfig?.minPasswordLength,
                        // The iLO shows this beside the login name, so the phone model survives
                        // there even though sixteen characters could not hold it.
                        displayNameFor = { created ->
                            dedicatedAccountDisplayName(username, Build.MODEL.orEmpty(), created)
                        },
                    )
                        .onSuccess {
                            createdAccount = it
                            step = STEP_IPMI
                        }
                        .onFailure { error = it.message }
                    busy = false
                }
            }
            STEP_DONE -> {
                hostRepository.saveHost(draft())
                saved = true
                onDone()
            }
            else -> step++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.wizard_title)) },
                navigationIcon = {
                    IconButton(onClick = { leave() }, enabled = !cancelling) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // The assisted path is the default because it gets the ports and privileges
                    // right by asking the server; the manual form stays one tap away for a server
                    // the assistant cannot reach.
                    if (step == STEP_IDENTITY) {
                        TextButton(onClick = onManualSetup) { Text(stringResource(R.string.wizard_manual)) }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (step > STEP_IDENTITY && step < STEP_DONE) {
                        OutlinedButton(
                            onClick = {
                                error = null
                                // Discard what this step found so returning to it reads again with
                                // whatever the user came back to change. An account that was
                                // created is kept: it exists on the iLO now.
                                when (step) {
                                    STEP_ACCOUNT -> if (createdAccount == null) inspection = null
                                    STEP_IPMI -> ipmiOutcome = null
                                    STEP_SSH_PORT -> portReachable = null
                                }
                                step--
                            },
                            enabled = !busy && !cancelling,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.wizard_previous))
                        }
                    }
                    Button(
                        onClick = { advance() },
                        enabled = !busy && !cancelling && when (step) {
                            STEP_IDENTITY -> hostname.isNotBlank()
                            // Refusing to go on would strand a user whose iLO answers on a port
                            // the scan cannot see; the warning is enough.
                            STEP_SSH_PORT -> sshPort.toIntOrNull() != null
                            STEP_CREDENTIALS -> username.isNotBlank() && password.isNotBlank()
                            STEP_ACCOUNT -> !createDedicated || dedicatedUsername.isNotBlank()
                            STEP_IPMI -> ipmiOutcome != null
                            else -> true
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(if (step == STEP_DONE) R.string.common_save else R.string.wizard_next))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(
                    R.string.wizard_step_progress,
                    step + 1,
                    STEP_TITLES.size,
                    stringResource(STEP_TITLES[step]),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer()
            if (busy || cancelling) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer()
            }
            if (cancelling) {
                Text(stringResource(R.string.wizard_removing_account), style = MaterialTheme.typography.bodySmall)
                Spacer()
            }

            when (step) {
                STEP_IDENTITY -> IdentityStep(
                    hostname = hostname,
                    onHostnameChange = { hostname = it },
                    networks = networks,
                    networkId = networkId,
                    onNetworkIdChange = { networkId = it },
                )

                STEP_SSH_PORT -> SshPortStep(
                    port = sshPort,
                    onPortChange = {
                        sshPort = it.filter { c -> c.isDigit() }
                        portReachable = null
                    },
                    reachable = portReachable,
                    busy = busy,
                    onScan = {
                        portReachable = null
                        scanAttempt++
                    },
                )

                STEP_CREDENTIALS -> CredentialsStep(
                    username = username,
                    onUsernameChange = { username = it },
                    password = password,
                    onPasswordChange = { password = it },
                )

                STEP_ACCOUNT -> AccountStep(
                    inspection = inspection,
                    created = createdAccount,
                    createDedicated = createDedicated,
                    onCreateDedicatedChange = { createDedicated = it },
                    dedicatedUsername = dedicatedUsername,
                    onDedicatedUsernameChange = {
                        dedicatedUsername = it
                        dedicatedEdited = true
                    },
                )

                STEP_IPMI -> IpmiStep(
                    outcome = ipmiOutcome,
                    busy = busy,
                    onRetry = {
                        ipmiOutcome = null
                        ipmiAttempt++
                    },
                )

                STEP_DONE -> SummaryStep(
                    serverName = serverName,
                    onServerNameChange = { serverName = it },
                    readName = inspection?.serverName.orEmpty(),
                    firmware = inspection?.firmware,
                    host = draft(),
                    network = network,
                    created = createdAccount,
                    ipmiOutcome = ipmiOutcome,
                )
            }

            error?.let {
                Spacer()
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    cancelError?.let { message ->
        AlertDialog(
            onDismissRequest = { cancelError = null },
            title = { Text(stringResource(R.string.wizard_cancel_failed_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.wizard_cancel_failed_message,
                        createdAccount?.username.orEmpty(),
                        message,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    cancelError = null
                    leave()
                }) { Text(stringResource(R.string.wizard_retry)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    cancelError = null
                    onBack()
                }) { Text(stringResource(R.string.wizard_exit_anyway)) }
            },
        )
    }

    // The suggestion follows the account being used, the device holding it, and what the iLO
    // already has — until the user takes the field over.
    LaunchedEffect(username, inspection, dedicatedEdited) {
        if (dedicatedEdited) return@LaunchedEffect
        val proposed = defaultDedicatedUsername(username, Build.MODEL.orEmpty())
        // With the account list in hand the suffix shown is the one that will really be used,
        // rather than a zero the creation step then quietly moves past.
        dedicatedUsername = inspection
            ?.let { nextFreeDedicatedUsername(proposed, it.accounts) }
            ?: proposed
    }

    // Each probe runs on arriving at its step, and again whenever its own result is cleared —
    // which is what the retry buttons do. Keying on `busy` instead would deadlock: the effect
    // sets it, so re-keying on it would cancel the very work it just started.
    LaunchedEffect(step, scanAttempt) {
        if (step != STEP_SSH_PORT || portReachable != null) return@LaunchedEffect
        busy = true
        portReachable = HostSetupRunner.probeTcpPort(draft(), sshPort.toIntOrNull() ?: 22)
        busy = false
    }

    LaunchedEffect(step, ipmiAttempt) {
        if (step != STEP_IPMI || ipmiOutcome != null) return@LaunchedEffect
        busy = true
        ipmiOutcome = HostSetupRunner.probeIpmi(draft())
        busy = false
    }
}

@Composable
private fun IdentityStep(
    hostname: String,
    onHostnameChange: (String) -> Unit,
    networks: List<NetworkConfig>,
    networkId: String,
    onNetworkIdChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = hostname,
        onValueChange = onHostnameChange,
        label = { Text(stringResource(R.string.wizard_ilo_address)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    Text(
        stringResource(R.string.wizard_name_later),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer()
    WizardNetworkPicker(networks, networkId, onNetworkIdChange)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WizardNetworkPicker(
    networks: List<NetworkConfig>,
    networkId: String,
    onNetworkIdChange: (String) -> Unit,
) {
    if (networks.isEmpty()) {
        Text(
            stringResource(R.string.wizard_no_network),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selected = networks.firstOrNull { it.id == networkId }
    val directAccess = stringResource(R.string.wizard_direct_access)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.name ?: directAccess,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.wizard_network)) },
            supportingText = {
                Text(selected?.let { stringResource(it.type.detailResource()) } ?: stringResource(R.string.wizard_direct_no_tunnel))
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(directAccess) },
                onClick = {
                    onNetworkIdChange("")
                    expanded = false
                },
            )
            networks.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(candidate.name) },
                    onClick = {
                        onNetworkIdChange(candidate.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SshPortStep(
    port: String,
    onPortChange: (String) -> Unit,
    reachable: Boolean?,
    busy: Boolean,
    onScan: () -> Unit,
) {
    Text(
        stringResource(R.string.wizard_ssh_explanation),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer()
    OutlinedTextField(
        value = port,
        onValueChange = onPortChange,
        label = { Text(stringResource(R.string.wizard_ssh_port)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    when (reachable) {
        true -> Text(stringResource(R.string.wizard_port_responds, port), style = MaterialTheme.typography.bodySmall)
        false -> Text(
            stringResource(R.string.wizard_port_no_response, port),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        null -> if (!busy) Text(stringResource(R.string.wizard_test_pending), style = MaterialTheme.typography.bodySmall)
    }
    Spacer()
    OutlinedButton(onClick = onScan, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.wizard_test_port))
    }
}

@Composable
private fun CredentialsStep(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
) {
    Text(
        stringResource(R.string.wizard_credentials_help),
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer()
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text(stringResource(R.string.wizard_existing_ilo_user)) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text(stringResource(R.string.wizard_password)) },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Offers a dedicated account, now that the iLO has said which names are taken.
 *
 * Asked after the credentials rather than beside them because the answer depends on what the
 * connection found: the proposed name already carries the suffix it will really be given.
 */
@Composable
private fun AccountStep(
    inspection: HostSetupRunner.IloInspection?,
    created: HostSetupRunner.CreatedAccount?,
    createDedicated: Boolean,
    onCreateDedicatedChange: (Boolean) -> Unit,
    dedicatedUsername: String,
    onDedicatedUsernameChange: (String) -> Unit,
) {
    if (created != null) {
        Text(stringResource(R.string.wizard_account_created, created.username))
        Spacer()
        Text(
            stringResource(R.string.wizard_account_secret_saved),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    Text(
        stringResource(R.string.wizard_credentials_verified, inspection?.accounts?.size ?: 0),
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(stringResource(R.string.wizard_create_dedicated_account))
            Text(
                stringResource(R.string.wizard_dedicated_account_help),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = createDedicated, onCheckedChange = onCreateDedicatedChange)
    }

    if (!createDedicated) return

    Spacer()
    OutlinedTextField(
        value = dedicatedUsername,
        onValueChange = onDedicatedUsernameChange,
        label = { Text(stringResource(R.string.wizard_account_name)) },
        isError = dedicatedUsername.length > IPMI_MAX_USERNAME_LENGTH,
        supportingText = {
            Text(
                stringResource(R.string.wizard_username_length, dedicatedUsername.length, IPMI_MAX_USERNAME_LENGTH),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    Text(
        stringResource(R.string.wizard_privileges_help),
        style = MaterialTheme.typography.bodySmall,
    )

    // Otherwise the IPMI step reports it as unavailable and the cause looks like the server.
    if (dedicatedAccountLosesIpmi(inspection?.accessConfig?.minPasswordLength)) {
        Spacer()
        Text(
                stringResource(
                    R.string.wizard_ipmi_password_limit,
                    inspection?.accessConfig?.minPasswordLength ?: 0,
                    IPMI_MAX_PASSWORD_LENGTH,
                ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun IpmiStep(
    outcome: HostSetupRunner.IpmiOutcome?,
    busy: Boolean,
    onRetry: () -> Unit,
) {
    when (outcome) {
        null -> Text(
            stringResource(if (busy) R.string.wizard_ipmi_test else R.string.wizard_ipmi_not_tested),
            style = MaterialTheme.typography.bodySmall,
        )
        is HostSetupRunner.IpmiOutcome.Available -> {
            Text(stringResource(R.string.wizard_ipmi_responds))
            Spacer()
            Text(
                stringResource(R.string.wizard_ipmi_privilege, stringResource(outcome.privilege.labelResource())),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is HostSetupRunner.IpmiOutcome.Unavailable -> {
            Text(stringResource(R.string.wizard_ipmi_disabled))
            Spacer()
            Text(
                stringResource(R.string.wizard_ipmi_unavailable, outcome.reason),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer()
            OutlinedButton(onClick = onRetry, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wizard_retest))
            }
        }
    }
}

/**
 * Names the server and shows what will be stored.
 *
 * The name is asked last because it is the one field the iLO can answer for: its own configured
 * name arrives with the inspection, and an address in a list of servers is what the user would
 * otherwise be left reading.
 */
@Composable
private fun SummaryStep(
    serverName: String,
    onServerNameChange: (String) -> Unit,
    readName: String,
    firmware: IloFirmware?,
    host: SshHost,
    network: NetworkConfig?,
    created: HostSetupRunner.CreatedAccount?,
    ipmiOutcome: HostSetupRunner.IpmiOutcome?,
) {
    OutlinedTextField(
        value = serverName,
        onValueChange = onServerNameChange,
        label = { Text(stringResource(R.string.wizard_server_name)) },
        supportingText = {
            Text(
                if (readName.isNotBlank()) stringResource(R.string.wizard_name_suggested, readName)
                else stringResource(R.string.wizard_name_fallback),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer()
    Text(stringResource(R.string.wizard_address_summary, host.hostname, host.port))
    Text(stringResource(R.string.wizard_https_port_summary, host.httpsPort))
    Text(
        stringResource(
            R.string.wizard_network_summary,
            network?.name ?: stringResource(R.string.wizard_direct_access),
        ),
    )
    Text(
        stringResource(
            R.string.wizard_account_summary,
            host.username,
            if (created != null) stringResource(R.string.wizard_created_suffix) else "",
        ),
    )
    Text(
        when (ipmiOutcome) {
            is HostSetupRunner.IpmiOutcome.Available -> stringResource(
                R.string.wizard_ipmi_summary_enabled,
                stringResource(ipmiOutcome.privilege.labelResource()),
            )
            else -> stringResource(R.string.wizard_ipmi_summary_disabled)
        },
    )
    firmware?.takeIf { it.version.isNotBlank() }?.let {
        Text(
            stringResource(
                R.string.wizard_firmware_summary,
                it.version,
                it.date.takeIf(String::isNotBlank)?.let { date -> " ($date)" }.orEmpty(),
            ),
        )
    }

    if (firmware != null && isOlderThanTested(firmware.version)) {
        Spacer()
        Text(
            stringResource(R.string.wizard_firmware_old_title),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.wizard_firmware_old_warning, firmware.version, TESTED_ILO_VERSION),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.wizard_firmware_update_help),
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer()
    Text(
        stringResource(R.string.wizard_not_saved_yet),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Spacer() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 8.dp))
}
