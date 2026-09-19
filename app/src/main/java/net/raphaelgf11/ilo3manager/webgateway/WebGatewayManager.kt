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

/** Offset added to a forced privileged port to get a deterministic, always-unprivileged bind port. */
private const val PRIVILEGED_REDIRECT_BASE_PORT = 20_000

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
    private val publicPorts = ConcurrentHashMap<String, Int>()

    /** publicPort -> (actualPort, exposeAllInterfaces), for tearing down the iptables redirect on stop. */
    private val redirects = ConcurrentHashMap<String, Triple<Int, Int, Boolean>>()

    private val _runningHostIds = MutableStateFlow<Set<String>>(emptySet())
    val runningHostIds: StateFlow<Set<String>> = _runningHostIds

    /** Starts (if needed) the local proxy for [host] and returns the port it's reachable on. */
    @Synchronized
    fun ensureStarted(
        context: Context,
        host: SshHost,
        exposeAllInterfaces: Boolean = false,
        forcedPort: Int? = null,
        useHttps: Boolean = false,
    ): Int {
        servers[host.id]?.let { if (it.isAlive) return publicPorts[host.id] ?: it.listeningPort }

        val needsPrivilegedRedirect = forcedPort != null && forcedPort < PRIVILEGED_PORT_THRESHOLD
        if (needsPrivilegedRedirect && !RootDetector.isProbablyRooted()) {
            throw IOException(
                "Le port $forcedPort est privilégié (< $PRIVILEGED_PORT_THRESHOLD) : appareil rooté requis, et aucun root n'a été détecté.",
            )
        }

        // A normal app process can't bind a privileged port even when the device is rooted (no
        // CAP_NET_BIND_SERVICE). So on a rooted device we instead bind an ordinary port and have
        // root install an iptables redirect from the requested privileged port to it. The bind
        // port is derived deterministically (not OS-assigned) so a leftover rule from a run that
        // ended without calling stop() (crash, force-kill, reinstall) can always be found and
        // removed by exact match before installing a fresh one — see [RootPortForwarder.redirect].
        val bindPort = when {
            needsPrivilegedRedirect -> PRIVILEGED_REDIRECT_BASE_PORT + forcedPort!!
            else -> forcedPort ?: 0
        }
        val server = IloHttpProxyServer(host, port = bindPort, exposeAllInterfaces = exposeAllInterfaces)
        if (useHttps) {
            server.enableHttps(SelfSignedCertificate.loadOrCreate(context), SelfSignedCertificate.keyPassword())
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)

        var publicPort = server.listeningPort
        if (needsPrivilegedRedirect) {
            val ok = RootPortForwarder.redirect(forcedPort!!, server.listeningPort, exposeAllInterfaces)
            if (!ok) {
                server.stop()
                throw IOException(
                    "Échec de la redirection root du port privilégié $forcedPort vers ${server.listeningPort} " +
                        "(la commande iptables via su a échoué).",
                )
            }
            publicPort = forcedPort
            redirects[host.id] = Triple(forcedPort, server.listeningPort, exposeAllInterfaces)
        }

        servers[host.id] = server
        publicPorts[host.id] = publicPort
        _runningHostIds.value = servers.keys.toSet()
        ContextCompat.startForegroundService(context, WebGatewayForegroundService.intent(context))
        return publicPort
    }

    fun portFor(hostId: String): Int? = servers[hostId]?.takeIf { it.isAlive }?.let { publicPorts[hostId] }

    @Synchronized
    fun stop(context: Context, hostId: String) {
        servers.remove(hostId)?.stop()
        LegacyTlsConnectionPool.evictAll()
        publicPorts.remove(hostId)
        redirects.remove(hostId)?.let { (publicPort, actualPort, exposeAllInterfaces) ->
            RootPortForwarder.removeRedirect(publicPort, actualPort, exposeAllInterfaces)
        }
        _runningHostIds.value = servers.keys.toSet()
        if (servers.isEmpty()) {
            context.stopService(WebGatewayForegroundService.intent(context))
        }
    }
}
