package net.raphaelgf11.ilo3manager.vpn

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.data.VpnType
import net.raphaelgf11.ilo3manager.ilo.ConnectionProgress
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Where a client should actually connect to reach a service, once tunnelling is accounted for. */
data class Endpoint(val host: String, val port: Int)

/**
 * Resolves the address a client should dial for a given host and port.
 *
 * Tunnelling is expressed as address translation rather than as a custom socket layer: every
 * network client in the app (JSch for the CLI and serial console, Bouncy Castle for the web
 * gateway) already takes a host and a port, so routing through a tunnel only requires handing them
 * a different address. Without a tunnel the address is returned unchanged.
 */
object HostTunnelManager {

    private class Tunnel(val session: Session) {
        /** target port on the iLO -> local port the SSH session forwards it from. */
        val forwards = ConcurrentHashMap<Int, Int>()
    }

    private class WgTunnel(val tunnel: wgtunnel.Tunnel) {
        val tcpForwards = ConcurrentHashMap<Int, Int>()
        val udpForwards = ConcurrentHashMap<Int, Int>()
    }

    private val tunnels = ConcurrentHashMap<String, Tunnel>()
    private val wgTunnels = ConcurrentHashMap<String, WgTunnel>()

    /**
     * The address to dial to reach [targetPort] on [host].
     *
     * For an SSH jump host this opens the tunnel on first use and allocates a local forwarded port
     * per target port, reusing it afterwards.
     */
    @Synchronized
    fun endpointFor(host: SshHost, targetPort: Int, udp: Boolean = false): Endpoint {
        when (host.vpnType) {
            VpnType.NONE -> return Endpoint(host.hostname, targetPort)
            VpnType.WIREGUARD -> return wireGuardEndpoint(host, targetPort, udp)
            VpnType.SSH_TUNNEL -> Unit
        }
        if (udp) {
            throw IOException("Un rebond SSH ne transporte que du TCP ; ce service utilise UDP.")
        }

        val config = SshTunnelConfig.fromJson(host.sshTunnelConfig)
        if (!config.isUsable) {
            throw IOException("Tunnel SSH incomplet : renseignez l'hôte, l'utilisateur et un mot de passe ou une clé.")
        }

        val tunnel = tunnels[host.id]?.takeIf { it.session.isConnected } ?: openTunnel(host, config)
        val localPort = tunnel.forwards.getOrPut(targetPort) {
            ConnectionProgress.report(host.id, "Ouverture du port relayé…")
            // Local port 0 lets the SSH library pick a free one and tell us which.
            tunnel.session.setPortForwardingL(0, host.hostname, targetPort)
        }
        return Endpoint("127.0.0.1", localPort)
    }

    /**
     * Opens the WireGuard tunnel on first use and publishes the requested service as a local
     * forwarded port, so callers dial it exactly as they would an SSH jump host.
     */
    private fun wireGuardEndpoint(host: SshHost, targetPort: Int, udp: Boolean): Endpoint {
        val config = runCatching { WireGuardConfigParser.parse(host.wireGuardConfig) }
            .getOrElse { throw IOException(it.message ?: "Configuration WireGuard illisible.") }

        val existing = wgTunnels[host.id]
        if (existing == null) ConnectionProgress.report(host.id, "Démarrage du tunnel WireGuard…")
        val tunnel = existing ?: WgTunnel(
            wgtunnel.Wgtunnel.start(
                WireGuardIpcConfig.render(config),
                config.addresses.joinToString(","),
                config.dnsServers.joinToString(","),
                (config.mtu ?: 0).toLong(),
            ),
        ).also { wgTunnels[host.id] = it }

        // Surface the handshake state: without it a peer that never answers is indistinguishable
        // from a reachable one, and the only symptom is a timeout much later.
        android.util.Log.d("WgTunnel", "status: " + runCatching { tunnel.tunnel.status() }.getOrElse { it.message })

        val cache = if (udp) tunnel.udpForwards else tunnel.tcpForwards
        val localPort = cache.getOrPut(targetPort) {
            ConnectionProgress.report(host.id, "Ouverture du port dans le tunnel…")
            val port = if (udp) {
                tunnel.tunnel.forwardUDP(host.hostname, targetPort.toLong())
            } else {
                tunnel.tunnel.forwardTCP(host.hostname, targetPort.toLong())
            }
            port.toInt()
        }
        return Endpoint("127.0.0.1", localPort)
    }

    /**
     * ICMP echo to [address], by whichever route this host is reached.
     *
     * Behind WireGuard the echo has to originate inside the userspace stack: the tunnel has no
     * system network interface, so the platform's own ping command would leave by the Wi-Fi and
     * never see it. Without a tunnel that command is exactly what is needed, since an ordinary
     * Android app cannot open a raw socket.
     *
     * An SSH jump host carries only TCP, so nothing can be pinged through one.
     */
    fun ping(host: SshHost, address: String, count: Int = 3, timeoutMs: Int = 1_000): Boolean {
        if (address.isBlank()) return false
        return when (host.vpnType) {
            VpnType.WIREGUARD -> {
                // Reuses the tunnel the rest of the app already opened for this host.
                val tunnel = wgTunnels[host.id]?.tunnel
                if (tunnel == null) {
                    android.util.Log.d("NicPing", "$address -> aucun tunnel ouvert")
                    return false
                }
                runCatching { tunnel.ping(address, count.toLong(), timeoutMs.toLong()) }
                    .onFailure { android.util.Log.d("NicPing", "$address : ${it.message}") }
                    .getOrDefault(false)
            }
            VpnType.SSH_TUNNEL -> false
            VpnType.NONE -> systemPing(address, count, timeoutMs)
        }
    }

    private fun systemPing(address: String, count: Int, timeoutMs: Int): Boolean = runCatching {
        val seconds = ((timeoutMs + 999) / 1000).coerceAtLeast(1)
        val process = ProcessBuilder("/system/bin/ping", "-c", "$count", "-W", "$seconds", address)
            .redirectErrorStream(true)
            .start()
        // The output has to be drained, not closed: ping writing a line into a closed pipe takes a
        // SIGPIPE and dies, which reads back as a failed ping however well the host answered.
        val output = process.inputStream.bufferedReader().use { it.readText() }
        // ping exits on its own once the count is reached, so this cannot hang indefinitely.
        val code = process.waitFor()
        if (code != 0) {
            android.util.Log.d("NicPing", "$address -> code $code: ${output.trim().takeLast(200)}")
        }
        code == 0
    }.getOrElse {
        android.util.Log.d("NicPing", "$address -> ${it.message}")
        false
    }

    /** True when the tunnel in use can carry UDP; SSH port forwarding cannot. */
    fun supportsUdp(host: SshHost): Boolean = when (host.vpnType) {
        VpnType.NONE -> true
        VpnType.WIREGUARD -> true
        VpnType.SSH_TUNNEL -> false
    }

    private fun openTunnel(host: SshHost, config: SshTunnelConfig): Tunnel {
        val hostId = host.id
        ConnectionProgress.report(hostId, "Connexion au rebond SSH ${config.host}…")
        val jsch = JSch()
        if (config.privateKey.isNotBlank()) {
            jsch.addIdentity(
                "tunnel-$hostId",
                config.privateKey.toByteArray(),
                null,
                config.passphrase.takeIf { it.isNotBlank() }?.toByteArray(),
            )
        }
        val session = jsch.getSession(config.username, config.host, config.port)
        session.setConfig("StrictHostKeyChecking", "no")
        if (config.password.isNotBlank()) session.setPassword(config.password)
        ConnectionProgress.report(hostId, "Authentification sur le rebond SSH…")
        session.connect(15_000)

        val tunnel = Tunnel(session)
        tunnels[hostId] = tunnel
        return tunnel
    }

    /** Tears the tunnel down; forwarded ports die with the session. */
    @Synchronized
    fun close(hostId: String) {
        tunnels.remove(hostId)?.let { runCatching { it.session.disconnect() } }
        wgTunnels.remove(hostId)?.let { runCatching { it.tunnel.close() } }
    }

    @Synchronized
    fun closeAll() {
        (tunnels.keys + wgTunnels.keys).toSet().forEach(::close)
    }
}
