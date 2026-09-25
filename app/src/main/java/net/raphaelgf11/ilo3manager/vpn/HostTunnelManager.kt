package net.raphaelgf11.ilo3manager.vpn

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import net.raphaelgf11.ilo3manager.data.NetworkConfig
import net.raphaelgf11.ilo3manager.data.NetworkType
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
 *
 * Tunnels belong to a [NetworkConfig], not to a host: several hosts behind one VPN share a single
 * tunnel, which is the point of naming networks. Each host that resolves an endpoint is recorded as
 * a user of that tunnel, and the tunnel only goes down once its last user has been released.
 */
object HostTunnelManager {

    /**
     * Seconds between keepalives on a tunnel kept open to be reached, not to reach.
     *
     * Twenty-five is WireGuard's own recommendation: below the thirty seconds most NAT tables
     * hold a UDP mapping for, and cheap enough at one small packet that nothing notices.
     */
    private const val KEEPALIVE_SECONDS = 25

    private class Tunnel(val session: Session) {
        /** "target host:port" -> local port the SSH session forwards it from. */
        val forwards = ConcurrentHashMap<String, Int>()
    }

    private class WgTunnel(val tunnel: wgtunnel.Tunnel, val hasKeepalive: Boolean) {
        val tcpForwards = ConcurrentHashMap<String, Int>()
        val udpForwards = ConcurrentHashMap<String, Int>()
    }

    private val tunnels = ConcurrentHashMap<String, Tunnel>()
    private val wgTunnels = ConcurrentHashMap<String, WgTunnel>()

    /** Hosts currently relying on each network's tunnel; see [TunnelUsers]. */
    private val users = TunnelUsers()

    /**
     * Where the named networks are read from.
     *
     * A lookup rather than a stored list, so an edited configuration is seen on the next connection
     * without anything having to notify this object. Installed once per process, early enough that
     * a background worker starting the process alone still resolves its hosts; see
     * `Ilo3Application`. Left empty a host can only fall back to its legacy fields.
     */
    @Volatile
    private var networks: () -> List<NetworkConfig> = { emptyList() }

    fun useNetworks(provider: () -> List<NetworkConfig>) {
        networks = provider
    }

    /**
     * The network a host is reached through, or null when it connects directly.
     *
     * A host pointing at a network that no longer exists also reads as null here: a dangling
     * reference must not make a status query throw. Only [endpointFor] treats it as the error it
     * is, at the point where connecting is actually being attempted.
     */
    private fun networkFor(host: SshHost): NetworkConfig? =
        if (host.networkId.isNotBlank()) networks().firstOrNull { it.id == host.networkId }
        else legacyNetworkFor(host)

    /**
     * The tunnel a host configured before networks were named, presented as one.
     *
     * The migration normally moves these into real networks at startup, so this only covers the
     * window before it has run — and the case of it never having run, in a process that never
     * installed a provider. Its id is scoped to the host, which reproduces the old behaviour of one
     * tunnel per host exactly.
     */
    @Suppress("DEPRECATION")
    private fun legacyNetworkFor(host: SshHost): NetworkConfig? = when (host.vpnType) {
        VpnType.NONE -> null
        VpnType.WIREGUARD -> NetworkConfig(
            id = "legacy:${host.id}",
            name = host.name,
            type = NetworkType.WIREGUARD,
            wireGuardConfig = host.wireGuardConfig,
        )
        VpnType.SSH_TUNNEL -> NetworkConfig(
            id = "legacy:${host.id}",
            name = host.name,
            type = NetworkType.SSH_TUNNEL,
            sshTunnelConfig = host.sshTunnelConfig,
        )
    }

    /**
     * The address to dial to reach [targetPort] on [host].
     *
     * For an SSH jump host this opens the tunnel on first use and allocates a local forwarded port
     * per target address, reusing it afterwards.
     */
    @Synchronized
    fun endpointFor(host: SshHost, targetPort: Int, udp: Boolean = false): Endpoint {
        val network = networkFor(host)
        if (network == null) {
            if (host.networkId.isNotBlank()) {
                throw IOException(
                    "Le réseau de ${host.name} est introuvable : il a été supprimé, " +
                        "ou la sauvegarde restaurée ne le contenait pas.",
                )
            }
            return Endpoint(host.hostname, targetPort)
        }

        return when (network.type) {
            // A secondary address is a real address on a real interface, so the target is dialled
            // directly — once the address is actually on the interface. Applying it here rather
            // than at save time means it survives a reboot, a Wi-Fi reconnection, or anything else
            // that quietly drops it, all of which would otherwise strand the host.
            NetworkType.SECONDARY_IP -> {
                SecondaryAddressManager.ensureApplied(network).getOrElse { throw it }
                claim(network, host)
                Endpoint(host.hostname, targetPort)
            }
            NetworkType.WIREGUARD -> wireGuardEndpoint(host, network, targetPort, udp)
            NetworkType.SSH_TUNNEL -> sshTunnelEndpoint(host, network, targetPort, udp)
        }
    }

    private fun sshTunnelEndpoint(
        host: SshHost,
        network: NetworkConfig,
        targetPort: Int,
        udp: Boolean,
    ): Endpoint {
        if (udp) {
            throw IOException("Un rebond SSH ne transporte que du TCP ; ce service utilise UDP.")
        }

        val config = SshTunnelConfig.fromJson(network.sshTunnelConfig)
        if (!config.isUsable) {
            throw IOException("Tunnel SSH incomplet : renseignez l'hôte, l'utilisateur et un mot de passe ou une clé.")
        }

        val tunnel = tunnels[network.id]?.takeIf { it.session.isConnected }
            ?: openTunnel(host, network, config)
        claim(network, host)
        val localPort = tunnel.forwards.getOrPut(forwardKey(host, targetPort)) {
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
    private fun wireGuardEndpoint(
        host: SshHost,
        network: NetworkConfig,
        targetPort: Int,
        udp: Boolean,
    ): Endpoint {
        // The configuration is parsed inside startWireGuard, which throws the readable message.
        val tunnel = startWireGuard(host, network)
        claim(network, host)

        // Surface the handshake state: without it a peer that never answers is indistinguishable
        // from a reachable one, and the only symptom is a timeout much later.
        android.util.Log.d("WgTunnel", "status: " + runCatching { tunnel.tunnel.status() }.getOrElse { it.message })

        val cache = if (udp) tunnel.udpForwards else tunnel.tcpForwards
        val localPort = cache.getOrPut(forwardKey(host, targetPort)) {
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
     * Makes the network listen on [tunnelPort] and relay whatever arrives to [localPort] here.
     *
     * The reverse of everything else in this object, and the only way an iLO's SNMP traps can reach
     * the phone: 162 is privileged, so no Android application may bind it — but inside WireGuard's
     * userspace stack there is no such thing as a privileged port, and the relay lands on a high
     * port the app can hold.
     *
     * Only WireGuard can do this. A jump host carries no UDP at all, and a secondary address is a
     * real interface where 162 is as forbidden as anywhere else.
     */
    @Synchronized
    fun listenInTunnel(host: SshHost, tunnelPort: Int, localPort: Int): Result<Unit> {
        val network = networkFor(host)
            ?: return Result.failure(
                IOException(
                    "${host.name} est joint directement : le port $tunnelPort ne peut pas être " +
                        "écouté par l'application, Android réservant les ports sous 1024.",
                ),
            )
        if (network.type != NetworkType.WIREGUARD) {
            return Result.failure(
                IOException(
                    "Le réseau « ${network.name} » est de type ${network.type.label} ; seul un " +
                        "tunnel WireGuard peut écouter un port privilégié pour l'application.",
                ),
            )
        }

        return runCatching {
            val tunnel = startWireGuard(host, network, forInbound = true).tunnel
            claim(network, host)
            tunnel.reverseUDP(tunnelPort.toLong(), localPort.toLong())
        }
    }

    /**
     * Brings the network's WireGuard tunnel up, or returns the one already running.
     *
     * [forInbound] forces a keepalive on a configuration that does not ask for one. Without it a
     * tunnel that only waits is unreachable from the far side: with nothing sent, the peer's
     * endpoint goes stale on the server and behind NAT the mapping expires, so packets addressed to
     * this phone have nowhere to go. Measured — an idle tunnel stopped answering pings from the
     * iLO entirely, while reporting a recent handshake.
     */
    private fun startWireGuard(
        host: SshHost,
        network: NetworkConfig,
        forInbound: Boolean = false,
    ): WgTunnel {
        wgTunnels[network.id]?.let { running ->
            if (!forInbound || running.hasKeepalive) return running
            // Up, but deaf. The interval is set when the device starts, so this has to go back.
            runCatching { running.tunnel.close() }
            wgTunnels.remove(network.id)
        }

        val parsed = runCatching { WireGuardConfigParser.parse(network.wireGuardConfig) }
            .getOrElse { throw IOException(it.message ?: "Configuration WireGuard illisible.") }
        val keepalive = parsed.persistentKeepaliveSeconds
            ?: KEEPALIVE_SECONDS.takeIf { forInbound }
        val config = parsed.copy(persistentKeepaliveSeconds = keepalive)

        ConnectionProgress.report(host.id, "Démarrage du tunnel WireGuard…")
        return WgTunnel(
            wgtunnel.Wgtunnel.start(
                WireGuardIpcConfig.render(config),
                config.addresses.joinToString(","),
                config.dnsServers.joinToString(","),
                (config.mtu ?: 0).toLong(),
            ),
            hasKeepalive = keepalive != null,
        ).also { wgTunnels[network.id] = it }
    }

    /**
     * Identifies a forwarded port by its destination, address included.
     *
     * The destination used to be the port alone, which was unambiguous while a tunnel served one
     * host. Shared between hosts it no longer is: two servers both asking for 443 would be handed
     * the same local port, and the second would silently reach the first.
     */
    private fun forwardKey(host: SshHost, targetPort: Int) = "${host.hostname}:$targetPort"

    /** Records that a host depends on this tunnel, so releasing another one cannot take it down. */
    private fun claim(network: NetworkConfig, host: SshHost) {
        users.claim(network.id, host.id)
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
        val network = networkFor(host) ?: return systemPing(address, count, timeoutMs)
        return when (network.type) {
            NetworkType.WIREGUARD -> {
                // Reuses the tunnel the rest of the app already opened for this network.
                val tunnel = wgTunnels[network.id]?.tunnel
                if (tunnel == null) {
                    android.util.Log.d("NicPing", "$address -> aucun tunnel ouvert")
                    return false
                }
                runCatching { tunnel.ping(address, count.toLong(), timeoutMs.toLong()) }
                    .onFailure { android.util.Log.d("NicPing", "$address : ${it.message}") }
                    .getOrDefault(false)
            }
            NetworkType.SSH_TUNNEL -> false
            // A secondary address leaves by a real interface, so the system's own ping reaches it.
            NetworkType.SECONDARY_IP -> systemPing(address, count, timeoutMs)
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
    fun supportsUdp(host: SshHost): Boolean =
        networkFor(host)?.type != NetworkType.SSH_TUNNEL

    private fun openTunnel(host: SshHost, network: NetworkConfig, config: SshTunnelConfig): Tunnel {
        ConnectionProgress.report(host.id, "Connexion au rebond SSH ${config.host}…")
        val jsch = JSch()
        if (config.privateKey.isNotBlank()) {
            jsch.addIdentity(
                "tunnel-${network.id}",
                config.privateKey.toByteArray(),
                null,
                config.passphrase.takeIf { it.isNotBlank() }?.toByteArray(),
            )
        }
        val session = jsch.getSession(config.username, config.host, config.port)
        session.setConfig("StrictHostKeyChecking", "no")
        if (config.password.isNotBlank()) session.setPassword(config.password)
        ConnectionProgress.report(host.id, "Authentification sur le rebond SSH…")
        session.connect(15_000)

        val tunnel = Tunnel(session)
        tunnels[network.id] = tunnel
        return tunnel
    }

    /**
     * Drops a host's claim on whatever tunnel carried it, tearing that tunnel down once no host
     * still needs it.
     *
     * Closing outright would be wrong now that a tunnel is shared: disconnecting one server would
     * cut the live sessions of every other server behind the same VPN.
     */
    @Synchronized
    fun releaseHost(hostId: String) {
        users.release(hostId).forEach(::closeNetwork)
    }

    /** Tears the tunnel down regardless of who is using it; forwarded ports die with the session. */
    @Synchronized
    fun closeNetwork(networkId: String) {
        users.forget(networkId)
        tunnels.remove(networkId)?.let { runCatching { it.session.disconnect() } }
        wgTunnels.remove(networkId)?.let { runCatching { it.tunnel.close() } }
    }

    @Synchronized
    fun closeAll() {
        (tunnels.keys + wgTunnels.keys).toSet().forEach(::closeNetwork)
    }
}
