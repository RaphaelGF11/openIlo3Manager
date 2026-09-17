package net.raphaelgf11.ilo3manager.webgateway

import android.content.Context
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.raphaelgf11.ilo3manager.data.SshHost
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val PRIVILEGED_PORT_THRESHOLD = 1024

/**
 * Owns one local loopback proxy server per host, started on demand and kept alive for as long as
 * the app process runs (or until explicitly stopped by the user). Mirrors
 * [net.raphaelgf11.ilo3manager.ssh.HostSessionStore]'s app-wide-singleton approach for the same
 * reasons: independent of navigation/composition lifecycle.
 *
 * The listening port is OS-assigned by default (bind to port 0), or can be pinned to a specific
 * port; a port below 1024 additionally requires the device to look rooted, since binding it needs
 * a capability normal apps don't have (see [RootDetector]). A foreground service is started for
 * as long as at least one gateway is running, so Android doesn't kill the process while the user
 * has switched to a browser to use it.
 */
object WebGatewayManager {

    private val servers = ConcurrentHashMap<String, IloHttpProxyServer>()

    private val _runningHostIds = MutableStateFlow<Set<String>>(emptySet())
    val runningHostIds: StateFlow<Set<String>> = _runningHostIds

    /** Starts (if needed) the local proxy for [host] and returns the port it's listening on. */
    @Synchronized
    fun ensureStarted(
        context: Context,
        host: SshHost,
        exposeAllInterfaces: Boolean = false,
        forcedPort: Int? = null,
        useHttps: Boolean = false,
    ): Int {
        servers[host.id]?.let { if (it.isAlive) return it.listeningPort }

        if (forcedPort != null && forcedPort < PRIVILEGED_PORT_THRESHOLD && !RootDetector.isProbablyRooted()) {
            throw IOException(
                "Le port $forcedPort est privilégié (< $PRIVILEGED_PORT_THRESHOLD) : appareil rooté requis, et aucun root n'a été détecté.",
            )
        }

        val server = IloHttpProxyServer(host, port = forcedPort ?: 0, exposeAllInterfaces = exposeAllInterfaces)
        if (useHttps) {
            server.enableHttps(SelfSignedCertificate.loadOrCreate(context), SelfSignedCertificate.keyPassword())
        }
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        } catch (e: IOException) {
            if (forcedPort != null && forcedPort < PRIVILEGED_PORT_THRESHOLD) {
                throw IOException(
                    "Échec de liaison au port privilégié $forcedPort malgré un appareil détecté comme rooté : " +
                        "cette application n'a pas la capacité réseau nécessaire (${e.message}).",
                )
            }
            throw e
        }
        servers[host.id] = server
        _runningHostIds.value = servers.keys.toSet()
        ContextCompat.startForegroundService(context, WebGatewayForegroundService.intent(context))
        return server.listeningPort
    }

    fun portFor(hostId: String): Int? = servers[hostId]?.takeIf { it.isAlive }?.listeningPort

    @Synchronized
    fun stop(context: Context, hostId: String) {
        servers.remove(hostId)?.stop()
        _runningHostIds.value = servers.keys.toSet()
        if (servers.isEmpty()) {
            context.stopService(WebGatewayForegroundService.intent(context))
        }
    }
}
