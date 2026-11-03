package net.raphaelgf11.ilo3manager.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.R
import net.raphaelgf11.ilo3manager.ilo.IloWebApiClient
import net.raphaelgf11.ilo3manager.ipmi.IpmiSensor
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ui.theme.Ilo3managerTheme

/**
 * Asks which server a newly placed panel should watch.
 *
 * It also previews the panel in each of its states: the widget only refreshes every so often, so
 * seeing beforehand what each indicator looks like is what makes a glance at the home screen
 * readable later.
 */
class FrontPanelConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // Cancelling is the correct default: if the user backs out, the launcher must not keep an
        // unconfigured widget.
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))

        val hosts = HostRepository(applicationContext).getHosts()
        val prefs = FrontPanelWidgetPrefs(applicationContext)

        setContent {
            Ilo3managerTheme {
                // Without a Surface the activity's own window background shows through, and a
                // screen that is read at night should not be the one white rectangle in the app.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ConfigScreen(
                        hosts = hosts,
                        onConfirm = { host ->
                            prefs.setHostId(widgetId, host.id)
                            FrontPanelWidget.requestRefresh(applicationContext, widgetId)
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                            )
                            finish()
                        },
                        confirmEnabled = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID,
                    )
                }
            }
        }
    }
}

private val PREVIEWS = listOf(
    R.string.widget_preview_unreachable to PanelState.DARK,
    R.string.widget_preview_off to PanelState(power = PowerLed.OFF),
    R.string.widget_preview_running to PanelState(power = PowerLed.ON, health = HealthLed.GREEN),
    R.string.widget_preview_uid to PanelState(power = PowerLed.ON, health = HealthLed.GREEN, uid = true),
    R.string.widget_preview_fault to PanelState(
        power = PowerLed.ON,
        health = HealthLed.AMBER,
        psus = listOf(Led.OFF, Led.AMBER),
        fans = listOf(Led.OFF, Led.OFF, Led.AMBER, Led.OFF, Led.OFF, Led.OFF),
        dimmsLeft = List(9) { if (it == 2) Led.AMBER else Led.OFF },
    ),
    // Critical shows red on the health LED only: the component indicators have no red state.
    R.string.widget_preview_critical to PanelState(
        power = PowerLed.ON,
        health = HealthLed.RED,
        procs = listOf(Led.AMBER, Led.OFF),
        overTemp = Led.AMBER,
    ),
)

@Composable
private fun ConfigScreen(
    hosts: List<SshHost>,
    onConfirm: (SshHost) -> Unit,
    confirmEnabled: Boolean,
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(hosts.firstOrNull()) }
    var preview by remember { mutableStateOf(2) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Android 15 draws every app edge to edge, and this screen has no Scaffold to inset it:
            // without this the title sits under the clock and the last button under the navigation
            // bar, where it cannot be tapped at all.
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.widget_panel_title), style = MaterialTheme.typography.titleLarge)

        val bitmap = remember(preview) {
            FrontPanelRenderer.render(PREVIEWS[preview].second, widthPx = 1080, resources = context.resources)
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = stringResource(PREVIEWS[preview].first),
            modifier = Modifier.fillMaxWidth(),
        )

        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PREVIEWS.forEachIndexed { index, (label, _) ->
                FilterChip(
                    selected = preview == index,
                    onClick = { preview = index },
                    label = { Text(stringResource(label)) },
                )
            }
        }

        Text(
            stringResource(R.string.widget_panel_ipmi_help),
            style = MaterialTheme.typography.bodySmall,
        )

        UsageAccessNotice()

        Text(stringResource(R.string.widget_server), style = MaterialTheme.typography.titleMedium)
        if (hosts.isEmpty()) {
            Text(stringResource(R.string.widget_no_hosts), style = MaterialTheme.typography.bodySmall)
        }
        hosts.forEach { host ->
            Row(host = host, selected = selected?.id == host.id, onSelect = { selected = host })
        }

        Button(
            onClick = { selected?.let(onConfirm) },
            enabled = confirmEnabled && selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.widget_add))
        }

        selected?.let { SensorDiagnostic(host = it) }
        selected?.let { WebApiProbe(host = it) }
    }
}

/**
 * Lists the sensors the BMC actually reports.
 *
 * Panel positions are matched against sensor names, and those names come from the firmware: when an
 * indicator lights for the wrong component, or stays dark for the right one, this is the only way
 * to see why.
 */
@Composable
private fun SensorDiagnostic(host: SshHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sensors by remember(host.id) { mutableStateOf<List<IpmiSensor>?>(null) }
    var error by remember(host.id) { mutableStateOf<String?>(null) }
    var loading by remember(host.id) { mutableStateOf(false) }

    androidx.compose.material3.OutlinedButton(
        onClick = {
            loading = true
            error = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { PanelReader.readAllSensors(host) } }
                    .onSuccess { sensors = it }
                    .onFailure { error = it.message ?: context.getString(R.string.widget_sensor_read_failed) }
                loading = false
            }
        },
        enabled = !loading && host.ipmiEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (loading) R.string.widget_reading_sensors else R.string.widget_detect_sensors))
    }

    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    sensors?.let { list ->
        Text(stringResource(R.string.widget_sensor_count, list.size), style = MaterialTheme.typography.titleSmall)
        list.forEach { sensor ->
            Text(
                "${sensor.name} — ${sensor.reading} — ${sensor.health}" +
                    if (sensor.eventReadingType != 0x01) " — ${context.getString(R.string.widget_sensor_states, sensor.states)}" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * JSON endpoints worth looking at, and what they were found to hold on an iLO 3 (firmware 1.94).
 *
 * `nic_info` lists each port's number, type and MAC address and nothing else — no link state. The
 * front panel's network indicators therefore cannot be driven from either interface the app can
 * reach: IPMI has no NIC sensor and the web API reports no link. `health_summary` is the useful
 * one, carrying the same per-subsystem verdicts the chassis panel itself shows.
 *
 * `rc_info` is what arms the remote console. The iLO's own Java applet asks for it before opening
 * anything, and answers with the console port, the virtual media port, and the keys guarding both;
 * port 17988 accepts a connection but stays silent until a session has been through here. It needs
 * the Advanced licence, so a refusal is itself the answer to whether the licence took.
 */
private val PROBE_ENDPOINTS = listOf(
    "/json/nic_info",
    "/json/health_summary",
    "/json/overview",
    "/json/rc_info",
)

/**
 * Reports which of those endpoints this iLO actually answers, and with what.
 *
 * One login for the whole probe, always logged out: this BMC keeps very few sessions and shares
 * them with SSH.
 */
@Composable
private fun WebApiProbe(host: SshHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember(host.id) { mutableStateOf<List<IloWebApiClient.ProbeResult>?>(null) }
    var error by remember(host.id) { mutableStateOf<String?>(null) }
    var loading by remember(host.id) { mutableStateOf(false) }

    androidx.compose.material3.OutlinedButton(
        onClick = {
            loading = true
            error = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        IloWebApiClient(host).probe(host.password, PROBE_ENDPOINTS)
                    }
                }
                    .onSuccess { results = it }
                    .onFailure { error = it.message ?: context.getString(R.string.widget_probe_failed) }
                loading = false
            }
        },
        enabled = !loading && host.password.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (loading) R.string.widget_probe_running else R.string.widget_probe_api))
    }

    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    results?.forEach { result ->
        Text(
            "${result.path} — ${context.getString(if (result.ok) R.string.widget_probe_accepted else R.string.widget_probe_refused)}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(result.body.take(4000), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Explains, and offers to grant, the usage access the live refresh depends on.
 *
 * Without it the widget still updates — on unlock and every half hour — so this is presented as a
 * choice rather than a requirement.
 */
@Composable
private fun UsageAccessNotice() {
    val context = LocalContext.current
    val granted = remember { mutableStateOf(PanelRefreshScheduler.hasUsageAccess(context)) }

    if (granted.value) {
        Text(
            stringResource(R.string.widget_usage_ready),
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    Text(
        stringResource(R.string.widget_usage_help),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        stringResource(R.string.widget_usage_privacy),
        style = MaterialTheme.typography.bodySmall,
    )
    androidx.compose.material3.OutlinedButton(
        onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.widget_usage_grant))
    }
}

@Composable
private fun Row(host: SshHost, selected: Boolean, onSelect: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(host.name)
            Text(
                stringResource(if (host.ipmiEnabled) R.string.widget_host_ipmi_enabled else R.string.widget_host_ipmi_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = if (host.ipmiEnabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}
