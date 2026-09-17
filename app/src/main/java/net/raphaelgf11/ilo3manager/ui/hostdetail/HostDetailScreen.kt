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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.raphaelgf11.ilo3manager.data.SettingsRepository
import net.raphaelgf11.ilo3manager.data.SshHost
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
fun HostDetailScreen(host: SshHost, settingsRepository: SettingsRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val controlSession = remember(host.id) { HostSessionStore.controlSessionFor(host) }
    val vspSession = remember(host.id) { HostSessionStore.vspSessionFor(host) }
    val vspState by vspSession.connectionState.collectAsStateWithLifecycle()

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
}
