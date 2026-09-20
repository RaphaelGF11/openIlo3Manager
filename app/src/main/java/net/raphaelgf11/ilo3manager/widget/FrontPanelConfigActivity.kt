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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.data.HostRepository
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
    "Injoignable" to PanelState.DARK,
    "Éteint" to PanelState(power = PowerLed.OFF),
    "En marche" to PanelState(power = PowerLed.ON, health = HealthLed.GREEN),
    "UID allumé" to PanelState(power = PowerLed.ON, health = HealthLed.GREEN, uid = true),
    "Défaut" to PanelState(
        power = PowerLed.ON,
        health = HealthLed.AMBER,
        psus = listOf(Led.OFF, Led.AMBER),
        fans = listOf(Led.OFF, Led.OFF, Led.AMBER, Led.OFF, Led.OFF, Led.OFF),
        dimmsLeft = List(9) { if (it == 2) Led.AMBER else Led.OFF },
    ),
    // Critical shows red on the health LED only: the component indicators have no red state.
    "Critique" to PanelState(
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
    var selected by remember { mutableStateOf(hosts.firstOrNull()) }
    var preview by remember { mutableStateOf(2) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Panneau avant", style = MaterialTheme.typography.titleLarge)

        val bitmap = remember(preview) {
            FrontPanelRenderer.render(PREVIEWS[preview].second, widthPx = 1080)
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = PREVIEWS[preview].first,
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
                    label = { Text(label) },
                )
            }
        }

        Text(
            "Le panneau est lu par IPMI uniquement : SSH serait trop lent pour un rafraîchissement " +
                "en arrière-plan. L'hôte choisi doit donc avoir IPMI activé.",
            style = MaterialTheme.typography.bodySmall,
        )

        UsageAccessNotice()

        Text("Serveur", style = MaterialTheme.typography.titleMedium)
        if (hosts.isEmpty()) {
            Text("Aucun hôte enregistré.", style = MaterialTheme.typography.bodySmall)
        }
        hosts.forEach { host ->
            Row(host = host, selected = selected?.id == host.id, onSelect = { selected = host })
        }

        Button(
            onClick = { selected?.let(onConfirm) },
            enabled = confirmEnabled && selected != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ajouter le widget")
        }

        selected?.let { SensorDiagnostic(host = it) }
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
                    .onFailure { error = it.message ?: "Lecture impossible" }
                loading = false
            }
        },
        enabled = !loading && host.ipmiEnabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (loading) "Lecture des capteurs…" else "Voir les capteurs détectés")
    }

    error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
    sensors?.let { list ->
        Text("${list.size} capteur(s)", style = MaterialTheme.typography.titleSmall)
        list.forEach { sensor ->
            Text(
                "${sensor.name} — ${sensor.reading} — ${sensor.health}" +
                    if (sensor.eventReadingType != 0x01) " — états 0x%02x".format(sensor.states) else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
            "Le panneau se rafraîchira à l'intervalle configuré tant que vous serez sur l'écran " +
                "d'accueil.",
            style = MaterialTheme.typography.bodySmall,
        )
        return
    }

    Text(
        "Pour se rafraîchir pendant que vous regardez l'écran d'accueil, le widget a besoin de " +
            "l'accès aux données d'utilisation — la seule façon sous Android de savoir que le " +
            "lanceur est au premier plan. Sans cette autorisation, il se met à jour au " +
            "déverrouillage et toutes les 30 minutes.",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "Elle sert uniquement à comparer l'application au premier plan au lanceur ; rien n'est " +
            "enregistré ni transmis.",
        style = MaterialTheme.typography.bodySmall,
    )
    androidx.compose.material3.OutlinedButton(
        onClick = {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Accorder l'accès aux données d'utilisation")
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
                if (host.ipmiEnabled) "IPMI activé" else "IPMI désactivé — le widget restera vide",
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
