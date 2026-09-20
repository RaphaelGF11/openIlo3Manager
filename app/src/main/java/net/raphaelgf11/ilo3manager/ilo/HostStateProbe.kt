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
            val ipmi = IpmiLanClient(endpoint.host, endpoint.port, host.username, host.password, host.ipmiPrivilege)
            try {
                ipmi.open()
                val status = ipmi.getChassisStatus()
                // Chassis status alone misses component faults — a dead power supply leaves every
                // one of its bits clear — so the dot would stay green, or blue when the locator is
                // lit, on a machine that is actually degraded.
                val health = maxOf(
                    IpmiSdrCache.worstHealth(ipmi, host.id),
                    when {
                        status.hasCriticalFault -> HealthLevel.CRITICAL
                        status.hasFault -> HealthLevel.DEGRADED
                        else -> HealthLevel.OK
                    },
                    compareBy { it.ordinal },
                )
                HostIndicator.from(HostIndicator.powerStateOf(status), health, status.identifyOn)
            } finally {
                ipmi.close()
            }
        } catch (_: Exception) {
            HostIndicator.UNKNOWN
        }
    }
}
