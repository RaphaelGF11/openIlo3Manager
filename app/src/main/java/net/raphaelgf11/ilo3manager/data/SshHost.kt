package net.raphaelgf11.ilo3manager.data

import net.raphaelgf11.ilo3manager.ipmi.IpmiPrivilege
import java.util.UUID

enum class AuthMethod {
    PASSWORD,
    PRIVATE_KEY,
}

data class SshHost(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val httpsPort: Int = 443,
    /**
     * Open the serial console session as soon as the host is opened. It is a second SSH session,
     * independent of the control one, so pre-opening it is a separate choice — and one that costs
     * a slot in the very small pool an iLO3 allows.
     */
    val alwaysOpenVsp: Boolean = false,
    /** Tab shown when the host is opened. */
    val defaultTab: HostTab = HostTab.POWER,
    /**
     * Expose only the web gateway, and ask for no credentials.
     *
     * Solves the bootstrapping case: reaching an iLO whose web interface no modern browser can
     * open, precisely in order to create the dedicated account or upload the key that the other
     * tabs would need. Authentication then happens in the browser, against the iLO's own login
     * page, so the app stores nothing.
     */
    val webGatewayOnly: Boolean = false,
    val username: String,
    val authMethod: AuthMethod,
    val password: String = "",
    val privateKey: String = "",
    val privateKeyPassphrase: String = "",
    val publicKey: String = "",
    /**
     * IPMI/DCMI over LAN is far faster than the SSH CLI for power state/actions, but iLO3 does
     * not enable it by default, so this is opt-in per host and never assumed available.
     */
    val ipmiEnabled: Boolean = false,
    val ipmiPort: Int = 623,
    /**
     * Privilege requested when opening an IPMI session. Operator is enough for power control and
     * the locator LED, so Administrator is never needed. This is only a request: the iLO caps the
     * level it grants by the account's own privileges, and an incomplete account is held at User —
     * read-only — however high this asks.
     */
    val ipmiPrivilege: IpmiPrivilege = IpmiPrivilege.OPERATOR,
    /** Set once the user has declined the offer to enable IPMI, so it isn't proposed again. */
    val ipmiPromptDismissed: Boolean = false,
    /**
     * Poll this host over IPMI from the host list to show its power/health state there. Requires
     * IPMI, which is the only source fast enough to poll every host without an SSH session each.
     */
    val showStateInList: Boolean = false,
    /**
     * Read the hardware tab from IPMI sensors rather than by walking the CLI tree. Far quicker
     * (the whole sensor repository in under two seconds, versus roughly fifteen over SSH) but it
     * shows sensors rather than inventory: no per-DIMM or per-CPU detail, no serial numbers.
     */
    val hardwareOverIpmi: Boolean = false,
    /**
     * Open the SSH session in the background once the IPMI dashboard has loaded, rather than
     * waiting for a tab to need it. Only when the user actually opens the host: polling the list
     * must never start a session per server.
     */
    val alwaysOpenSsh: Boolean = false,
    /**
     * Run the background monitoring check over IPMI instead of SSH.
     *
     * The check opens a fresh connection every round, per monitored host. Over SSH that costs
     * seconds and one of the iLO's very few concurrent sessions; over IPMI it is a handful of UDP
     * exchanges against a repository already cached. It reports sensor health rather than the CLI's
     * component inventory, so a notification names fewer components — the trade the hardware tab
     * already makes.
     */
    val notificationsOverIpmi: Boolean = false,
    val vpnType: VpnType = VpnType.NONE,
    /**
     * Each tunnel type keeps its own field rather than sharing one.
     *
     * A single shared field leaked across types: after configuring an SSH jump host, selecting
     * WireGuard showed that tunnel's JSON — including its password — in the WireGuard text area,
     * which is not masked. Separate fields also mean switching type back and forth no longer
     * discards a configuration. Both hold secrets, so both are encrypted with the rest of the
     * record.
     */
    val wireGuardConfig: String = "",
    val sshTunnelConfig: String = "",
    /**
     * One address per embedded network port, in panel order, blank where unknown.
     *
     * The front panel's network indicators show link, which nothing the app can reach reports:
     * IPMI has no NIC sensor and iLO's own API gives each port's MAC and nothing more. Pinging an
     * address the user assigns to a port is an approximation of that — it proves the operating
     * system answers on that port, not that the cable is live — but it is the only signal
     * available, and only the user knows which address sits on which port.
     */
    val nicAddresses: List<String> = List(4) { "" },
)

/** Kinds of tunnel a host can be reached through. */
enum class VpnType {
    NONE,
    WIREGUARD,

    /**
     * Reach the host by forwarding ports through an SSH jump host. Carries TCP only, so a host
     * using it cannot use IPMI, which is UDP.
     */
    SSH_TUNNEL,
}
