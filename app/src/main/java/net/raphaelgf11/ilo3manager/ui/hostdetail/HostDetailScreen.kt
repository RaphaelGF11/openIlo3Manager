package net.raphaelgf11.ilo3manager.ui.hostdetail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.HostTab
import net.raphaelgf11.ilo3manager.data.SettingsRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ilo.IloWebApiClient
import net.raphaelgf11.ilo3manager.ssh.ConnectionState
import net.raphaelgf11.ilo3manager.ssh.HostSessionStore
import net.raphaelgf11.ilo3manager.ui.console.ConsoleTab
import net.raphaelgf11.ilo3manager.ui.dashboard.PowerHealthTab
import net.raphaelgf11.ilo3manager.ui.hardware.HardwareTab
import net.raphaelgf11.ilo3manager.ui.vsp.VspTab
import net.raphaelgf11.ilo3manager.ui.webgateway.WebGatewayTab
import net.raphaelgf11.ilo3manager.webgateway.WebGatewayManager

/** Tabs available for a host: everything, or the gateway alone when no credentials are stored. */
private fun tabsFor(host: SshHost): List<HostTab> =
    if (host.webGatewayOnly) listOf(HostTab.WEB) else HostTab.entries

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailScreen(
    initialHost: SshHost,
    settingsRepository: SettingsRepository,
    hostRepository: HostRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Held as state, not read straight from the parameter: enabling IPMI rewrites the stored host
    // mid-session, and the screen must reflect that without waiting for a restart.
    var host by remember(initialHost.id) { mutableStateOf(initialHost) }
    val tabs = tabsFor(host)
    var selectedTab by remember(host.id) {
        mutableIntStateOf(tabs.indexOf(host.defaultTab).coerceAtLeast(0))
    }
    val controlSession = remember(host.id) { HostSessionStore.controlSessionFor(host) }
    val vspSession = remember(host.id) { HostSessionStore.vspSessionFor(host) }
    val vspState by vspSession.connectionState.collectAsStateWithLifecycle()
    val controlState by controlSession.connectionState.collectAsStateWithLifecycle()

    // Offered once the SSH session is actually up, which is also the moment the user has just
    // waited through the latency IPMI would remove — the most useful time to mention it.
    var showIpmiSuggestion by remember(host.id) { mutableStateOf(false) }
    var ipmiBusy by remember { mutableStateOf(false) }
    var ipmiError by remember { mutableStateOf<String?>(null) }

    // The serial console has its own SSH session, so opening the control one does not help it.
    // Started here rather than in the VSP tab: the point is to have it ready before the tab is
    // opened. Only on entering a host, never from the list.
    LaunchedEffect(host.id, host.alwaysOpenVsp) {
        if (host.alwaysOpenVsp && !host.webGatewayOnly) vspSession.connectIfNeeded()
    }

    LaunchedEffect(controlState, host.ipmiEnabled, host.ipmiPromptDismissed) {
        if (controlState == ConnectionState.CONNECTED && !host.ipmiEnabled &&
            !host.ipmiPromptDismissed && !host.webGatewayOnly
        ) {
            showIpmiSuggestion = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(host.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (vspState == ConnectionState.CONNECTED) {
                        IconButton(onClick = { vspSession.exitVsp() }) {
                            Text("E(", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    IconButton(
                        onClick = {
                            controlSession.disconnect()
                            vspSession.disconnect()
                            WebGatewayManager.stop(context, host.id)
                            onBack()
                        },
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Fermer la connexion")
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(tab.title) },
                    )
                }
            }
            androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                when (tabs.getOrNull(selectedTab)) {
                    HostTab.POWER -> PowerHealthTab(controlSession, settingsRepository)
                    HostTab.VSP -> VspTab(vspSession)
                    HostTab.CONSOLE -> ConsoleTab(controlSession)
                    HostTab.HARDWARE -> HardwareTab(controlSession)
                    HostTab.WEB, null -> WebGatewayTab(host)
                }
            }
        }
    }

    if (showIpmiSuggestion) {
        IpmiSuggestionDialog(
            canEnable = host.password.isNotBlank(),
            busy = ipmiBusy,
            error = ipmiError,
            onEnable = {
                ipmiBusy = true
                ipmiError = null
                scope.launch {
                    try {
                        // iLO3's SSH CLI has no IPMI property, so this goes through the device's
                        // own JSON API over the legacy-TLS client (see IloWebApiClient).
                        withContext(Dispatchers.IO) {
                            IloWebApiClient(host).setIpmiEnabled(host.password, true)
                        }
                        val updated = host.copy(ipmiEnabled = true)
                        hostRepository.saveHost(updated)
                        // Push it into the already-running session too, whose controller captured
                        // the host when it was first created.
                        HostSessionStore.updateHost(updated)
                        host = updated
                        showIpmiSuggestion = false
                    } catch (e: Exception) {
                        ipmiError = e.message ?: "Activation impossible"
                    } finally {
                        ipmiBusy = false
                    }
                }
            },
            onLater = { showIpmiSuggestion = false },
            onNeverAsk = {
                val updated = host.copy(ipmiPromptDismissed = true)
                hostRepository.saveHost(updated)
                HostSessionStore.updateHost(updated)
                host = updated
                showIpmiSuggestion = false
            },
        )
    }
}
