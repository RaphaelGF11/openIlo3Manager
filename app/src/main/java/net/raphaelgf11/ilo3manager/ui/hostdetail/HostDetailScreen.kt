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

private val TAB_TITLES = listOf("Alim", "VSP", "SSH", "HW", "Web")

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
    var selectedTab by remember { mutableIntStateOf(0) }
    val controlSession = remember(host.id) { HostSessionStore.controlSessionFor(host) }
    val vspSession = remember(host.id) { HostSessionStore.vspSessionFor(host) }
    val vspState by vspSession.connectionState.collectAsStateWithLifecycle()
    val controlState by controlSession.connectionState.collectAsStateWithLifecycle()

    // Offered once the SSH session is actually up, which is also the moment the user has just
    // waited through the latency IPMI would remove — the most useful time to mention it.
    var showIpmiSuggestion by remember(host.id) { mutableStateOf(false) }
    var ipmiBusy by remember { mutableStateOf(false) }
    var ipmiError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(controlState, host.ipmiEnabled, host.ipmiPromptDismissed) {
        if (controlState == ConnectionState.CONNECTED && !host.ipmiEnabled && !host.ipmiPromptDismissed) {
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
                TAB_TITLES.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }
            androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> PowerHealthTab(controlSession, settingsRepository)
                    1 -> VspTab(vspSession)
                    2 -> ConsoleTab(controlSession)
                    3 -> HardwareTab(controlSession)
                    4 -> WebGatewayTab(host)
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
