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
     * the locator LED, so asking for Administrator would needlessly require granting the iLO
     * account every privilege.
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
