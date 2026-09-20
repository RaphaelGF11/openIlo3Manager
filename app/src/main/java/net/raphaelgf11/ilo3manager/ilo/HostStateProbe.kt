package net.raphaelgf11.ilo3manager.ilo

import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ipmi.IpmiLanClient
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager

/**
 * Reads a host's state without opening a session the app then has to manage.
 *
 * Only IPMI is used: the host list may show several machines at once, and an SSH connection each
 * would cost seconds per host and consume iLO3's very small pool of concurrent sessions.
 */
object HostStateProbe {

    /** True when this host can be polled from the list at all. */
    fun isPollable(host: SshHost): Boolean =
        host.showStateInList && host.ipmiEnabled && host.password.isNotBlank() &&
            HostTunnelManager.supportsUdp(host)

    fun probe(host: SshHost): HostIndicator {
        if (!isPollable(host)) return HostIndicator.UNKNOWN
        return try {
            val endpoint = HostTunnelManager.endpointFor(host, host.ipmiPort, udp = true)
            val ipmi = IpmiLanClient(endpoint.host, endpoint.port, host.username, host.password)
            try {
                ipmi.open()
                HostIndicator.from(ipmi.getChassisStatus())
            } finally {
                ipmi.close()
            }
        } catch (_: Exception) {
            HostIndicator.UNKNOWN
        }
    }
}
