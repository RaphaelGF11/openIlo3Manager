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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import net.raphaelgf11.ilo3manager.data.SettingsRepository
import net.raphaelgf11.ilo3manager.R
import net.raphaelgf11.ilo3manager.ilo.ControlSessionController
import net.raphaelgf11.ilo3manager.ilo.HealthLevel
import net.raphaelgf11.ilo3manager.ilo.PowerAction
import net.raphaelgf11.ilo3manager.ilo.PowerState
import net.raphaelgf11.ilo3manager.ssh.ConnectionState

@Composable
fun PowerHealthTab(controller: ControlSessionController, settingsRepository: SettingsRepository) {
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val dashboardState by controller.dashboardState.collectAsStateWithLifecycle()
    val error by controller.errorMessage.collectAsStateWithLifecycle()
    val powerState by controller.powerState.collectAsStateWithLifecycle()
    val health by controller.overallHealth.collectAsStateWithLifecycle()
    val indicator by controller.indicator.collectAsStateWithLifecycle()
    val identifyOn by controller.identifyOn.collectAsStateWithLifecycle()
    val progress by controller.progress.collectAsStateWithLifecycle()
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
    val refreshSeconds = settings.refreshSecondsFor(controller.usesIpmi)
    LaunchedEffect(controller, dashboardState, refreshSeconds) {
        if (dashboardState != ConnectionState.CONNECTED) return@LaunchedEffect
        val delayMs = refreshSeconds * 1_000L
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
        when (dashboardState) {
            ConnectionState.CONNECTING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator()
                Text(progress ?: stringResource(R.string.common_connecting))
            }
            ConnectionState.ERROR, ConnectionState.DISCONNECTED -> {
                Text(error ?: stringResource(R.string.power_not_connected))
                Button(onClick = { controller.connectAndLoadDashboard() }) {
                    Text(stringResource(R.string.power_reconnect))
                }
            }
            ConnectionState.CONNECTED -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusDot(indicator = indicator, size = 16.dp)
                    Text(
                        text = when (health) {
                            HealthLevel.OK -> stringResource(R.string.health_normal)
                            HealthLevel.DEGRADED -> stringResource(R.string.health_degraded)
                            HealthLevel.CRITICAL -> stringResource(R.string.health_critical)
                            HealthLevel.UNKNOWN -> stringResource(R.string.health_unknown)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = when (powerState) {
                            PowerState.ON -> stringResource(R.string.power_state_on)
                            PowerState.OFF -> stringResource(R.string.power_state_off)
                            PowerState.UNKNOWN -> stringResource(R.string.power_state_unknown)
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
                        Text(stringResource(R.string.power_start))
                    }
                    OutlinedButton(
                        onClick = { pendingAction = PowerAction.RESET },
                        enabled = !actionInProgress && powerState == PowerState.ON,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.power_restart))
                    }
                    OutlinedButton(
                        onClick = { pendingAction = PowerAction.OFF },
                        enabled = !actionInProgress && powerState == PowerState.ON,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.power_stop))
                    }
                    OutlinedButton(
                        onClick = { pendingAction = PowerAction.FORCE_OFF },
                        enabled = !actionInProgress && powerState == PowerState.ON,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.power_force_stop))
                    }
                    if (controller.canReadIdentify) {
                        // The state is readable, so one button that reflects it is honest.
                        OutlinedButton(
                            onClick = { controller.setIdentify(!identifyOn) },
                            enabled = !actionInProgress,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(
                                    if (identifyOn) R.string.power_uid_turn_off else R.string.power_uid_turn_on,
                                ),
                            )
                        }
                    } else {
                        // Over SSH the LED can be set but not read, so a single toggle would have
                        // to guess which way it is pointing; two explicit actions cannot be wrong.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = { controller.setIdentify(true) },
                                enabled = !actionInProgress,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.power_uid_on))
                            }
                            OutlinedButton(
                                onClick = { controller.setIdentify(false) },
                                enabled = !actionInProgress,
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(stringResource(R.string.power_uid_off))
                            }
                        }
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
                        Text(stringResource(R.string.common_refresh))
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
