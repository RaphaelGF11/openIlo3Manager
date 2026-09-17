package net.raphaelgf11.ilo3manager.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import net.raphaelgf11.ilo3manager.data.SettingsRepository
import net.raphaelgf11.ilo3manager.ilo.ControlSessionController
import net.raphaelgf11.ilo3manager.ilo.HealthLevel
import net.raphaelgf11.ilo3manager.ilo.PowerAction
import net.raphaelgf11.ilo3manager.ilo.PowerState
import net.raphaelgf11.ilo3manager.ssh.ConnectionState

@Composable
fun PowerHealthTab(controller: ControlSessionController, settingsRepository: SettingsRepository) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val connectionState by controller.connectionState.collectAsStateWithLifecycle()
    val error by controller.errorMessage.collectAsStateWithLifecycle()
    val powerState by controller.powerState.collectAsStateWithLifecycle()
    val health by controller.overallHealth.collectAsStateWithLifecycle()
    val actionInProgress by controller.powerActionInProgress.collectAsStateWithLifecycle()
    val refreshing by controller.dashboardRefreshing.collectAsStateWithLifecycle()
    var pendingAction by remember { mutableStateOf<PowerAction?>(null) }

    LaunchedEffect(Unit) {
        controller.connectAndLoadDashboard()
    }

    // Auto-refresh AUTO_REFRESH_DELAY_MS after each refresh *finishes* (not after it started),
    // for as long as this tab stays composed (i.e. in the foreground). Restarts its own wait
    // whenever the tab re-enters composition (e.g. switching back from another tab), so it
    // doesn't depend on a refresh-completion transition that may never come if nothing is
    // in flight when the tab reappears. Cancelled automatically when the user switches tabs.
    LaunchedEffect(controller, connectionState, settings.autoRefreshSeconds) {
        if (connectionState != ConnectionState.CONNECTED) return@LaunchedEffect
        val delayMs = settings.autoRefreshSeconds * 1_000L
        controller.dashboardRefreshing.first { isRefreshing -> !isRefreshing }
        while (true) {
            delay(delayMs)
            controller.refreshDashboard()
            controller.dashboardRefreshing.first { isRefreshing -> !isRefreshing }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        when (connectionState) {
            ConnectionState.CONNECTING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator()
                Text("Connexion en cours…")
            }
            ConnectionState.ERROR, ConnectionState.DISCONNECTED -> {
                Text(error ?: "Non connecté")
                Button(onClick = { controller.connectAndLoadDashboard() }) {
                    Text("Reconnexion")
                }
            }
            ConnectionState.CONNECTED -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HealthLed(health = health)
                    Text(
                        text = when (health) {
                            HealthLevel.OK -> "État général : normal"
                            HealthLevel.DEGRADED -> "État général : dégradé (erreur non critique)"
                            HealthLevel.CRITICAL -> "État général : critique"
                            HealthLevel.UNKNOWN -> "État général : inconnu"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = when (powerState) {
                            PowerState.ON -> "Alimentation : allumé"
                            PowerState.OFF -> "Alimentation : éteint"
                            PowerState.UNKNOWN -> "Alimentation : inconnue"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { controller.performPowerAction(PowerAction.ON) },
                        enabled = !actionInProgress && powerState != PowerState.ON,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Démarrer")
                    }
                    OutlinedButton(
                        onClick = { pendingAction = PowerAction.RESET },
                        enabled = !actionInProgress && powerState == PowerState.ON,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Redémarrer")
                    }
                    OutlinedButton(
                        onClick = { pendingAction = PowerAction.OFF },
                        enabled = !actionInProgress && powerState == PowerState.ON,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Arrêter (normal)")
                    }
                    OutlinedButton(
                        onClick = { pendingAction = PowerAction.FORCE_OFF },
                        enabled = !actionInProgress && powerState == PowerState.ON,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Forcer l'arrêt")
                    }
                    OutlinedButton(
                        onClick = { controller.refreshDashboard() },
                        enabled = !actionInProgress && !refreshing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
                        }
                        Text("Rafraîchir")
                    }
                }
            }
        }
    }

    pendingAction?.let { action ->
        PowerActionConfirmationDialog(
            action = action,
            onConfirm = {
                controller.performPowerAction(action)
                pendingAction = null
            },
            onDismiss = { pendingAction = null },
        )
    }
}
