package net.raphaelgf11.ilo3manager.ui.webgateway

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.data.WebGatewaySettings
import net.raphaelgf11.ilo3manager.data.WebGatewaySettingsRepository
import net.raphaelgf11.ilo3manager.webgateway.WebGatewayManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Controls the local HTTPS-to-legacy-TLS gateway (see [WebGatewayManager]) and hands off to the
 * user's own browser to actually view iLO's web UI — a dedicated, full-featured browser renders
 * iLO's old frameset-based markup far more reliably than an embedded WebView. This is a
 * secondary, "advanced access" affordance; the app's own SSH-backed tabs remain the primary,
 * Android-native experience.
 */
@Composable
fun WebGatewayTab(host: SshHost) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runningHostIds by WebGatewayManager.runningHostIds.collectAsState()
    val isRunning = host.id in runningHostIds
    var port by remember(host.id) { mutableStateOf(WebGatewayManager.portFor(host.id)) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val settingsRepository = remember(context) { WebGatewaySettingsRepository(context) }
    val savedSettings = remember(host.id) { settingsRepository.settingsFor(host.id) }
    var exposeAllInterfaces by remember(host.id) { mutableStateOf(savedSettings.exposeAllInterfaces) }
    var useHttps by remember(host.id) { mutableStateOf(savedSettings.useHttps) }
    var forcedPortText by remember(host.id) { mutableStateOf(savedSettings.forcedPort?.toString() ?: "") }
    var runningWithHttps by remember { mutableStateOf(false) }

    // Persist as soon as an option changes, so it survives leaving the tab or restarting the app
    // even if the gateway is never started.
    fun persist() {
        settingsRepository.save(
            host.id,
            WebGatewaySettings(
                exposeAllInterfaces = exposeAllInterfaces,
                useHttps = useHttps,
                forcedPort = forcedPortText.toIntOrNull(),
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (isRunning) "Passerelle : en cours d'exécution" else "Passerelle : arrêtée",
            style = MaterialTheme.typography.titleMedium,
        )
        if (isRunning && port != null) {
            val address = if (exposeAllInterfaces) lanIpv4Address() ?: "127.0.0.1" else "127.0.0.1"
            val scheme = if (runningWithHttps) "https" else "http"
            Text("Adresse : $scheme://$address:$port")
        }
        Text(
            "Ouvre l'interface web de l'iLO (${host.hostname}:${host.httpsPort}) via une passerelle locale, " +
                "car son TLS trop ancien n'est accepté par aucun navigateur moderne directement.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Exposer sur toutes les interfaces (y compris IPv6)")
                if (exposeAllInterfaces) {
                    Text(
                        "Attention : la passerelle ne sera alors plus limitée à cet appareil — tout " +
                            "autre appareil du même réseau pourra y accéder sans authentification.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(
                checked = exposeAllInterfaces,
                onCheckedChange = { exposeAllInterfaces = it; persist() },
                enabled = !isRunning,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("HTTPS local (certificat auto-signé)", modifier = Modifier.weight(1f))
            Switch(
                checked = useHttps,
                onCheckedChange = { useHttps = it; persist() },
                enabled = !isRunning,
            )
        }

        OutlinedTextField(
            value = forcedPortText,
            onValueChange = { forcedPortText = it.filter { c -> c.isDigit() }; persist() },
            label = { Text("Forcer un port (optionnel, sinon aléatoire)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Un port < 1024 est privilégié et nécessite un appareil rooté.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        if (isRunning) {
                            withContext(Dispatchers.IO) { WebGatewayManager.stop(context, host.id) }
                            port = null
                        } else {
                            val requestedPort = forcedPortText.toIntOrNull()
                            port = withContext(Dispatchers.IO) {
                                WebGatewayManager.ensureStarted(
                                    context = context,
                                    host = host,
                                    exposeAllInterfaces = exposeAllInterfaces,
                                    forcedPort = requestedPort,
                                    useHttps = useHttps,
                                )
                            }
                            runningWithHttps = useHttps
                        }
                    } catch (e: Exception) {
                        error = e.message ?: "Échec de la passerelle"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isRunning) "Arrêter la passerelle" else "Démarrer la passerelle")
        }

        OutlinedButton(
            onClick = {
                val currentPort = port ?: return@OutlinedButton
                val scheme = if (runningWithHttps) "https" else "http"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://127.0.0.1:$currentPort/"))
                context.startActivity(intent)
            },
            enabled = isRunning && port != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ouvrir dans le navigateur")
        }
    }
}

/** This device's LAN IPv4 address, for display when the gateway is exposed on all interfaces. */
private fun lanIpv4Address(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
