package net.raphaelgf11.ilo3manager.webgateway

import android.content.Context
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.raphaelgf11.ilo3manager.data.SshHost
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns one local loopback proxy server per host, started on demand and kept alive for as long as
 * the app process runs (or until explicitly stopped by the user). Mirrors
 * [net.raphaelgf11.ilo3manager.ssh.HostSessionStore]'s app-wide-singleton approach for the same
 * reasons: independent of navigation/composition lifecycle.
 *
 * The listening port is always OS-assigned (bind to port 0) rather than derived from the host,
 * so it's genuinely unpredictable from outside the device. A foreground service is started for
 * as long as at least one gateway is running, so Android doesn't kill the process while the user
 * has switched to a browser to use it.
 */
object WebGatewayManager {

    private val servers = ConcurrentHashMap<String, IloHttpProxyServer>()

    private val _runningHostIds = MutableStateFlow<Set<String>>(emptySet())
    val runningHostIds: StateFlow<Set<String>> = _runningHostIds

    /** Starts (if needed) the local proxy for [host] and returns the port it's listening on. */
    @Synchronized
    fun ensureStarted(context: Context, host: SshHost): Int {
        servers[host.id]?.let { if (it.isAlive) return it.listeningPort }

        val server = IloHttpProxyServer(host, port = 0)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
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
