package net.raphaelgf11.ilo3manager.ui.webgateway

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.webgateway.WebGatewayManager

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
            Text("Adresse locale : 127.0.0.1:$port")
        }
        Text(
            "Ouvre l'interface web de l'iLO (${host.hostname}:${host.httpsPort}) via une passerelle locale, " +
                "car son TLS trop ancien n'est accepté par aucun navigateur moderne directement.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
        }

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
                            port = withContext(Dispatchers.IO) { WebGatewayManager.ensureStarted(context, host) }
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
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$currentPort/"))
                context.startActivity(intent)
            },
            enabled = isRunning && port != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Ouvrir dans le navigateur")
        }
    }
}
