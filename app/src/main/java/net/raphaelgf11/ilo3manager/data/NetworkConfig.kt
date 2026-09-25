package net.raphaelgf11.ilo3manager.data

import java.util.UUID

/** Ways of reaching a host that is not simply on the same network as the phone. */
enum class NetworkType(val label: String, val detail: String) {
    WIREGUARD(
        "WireGuard",
        "Tunnel chiffré porté par l'application, sans interface système ni consentement VPN. " +
            "Transporte UDP, donc IPMI passe.",
    ),

    /**
     * Reach hosts by forwarding ports through an SSH jump host. Carries TCP only, so a host on such
     * a network cannot use IPMI, which is UDP.
     */
    SSH_TUNNEL(
        "Rebond SSH",
        "Relaie les ports au travers d'un serveur intermédiaire. TCP seulement : IPMI ne passera pas.",
    ),

    /**
     * Add a real secondary address to a system interface, rather than tunnelling.
     *
     * The only option that gives the phone an address the network can route back to, which is what
     * some protocols need. It requires root because adding an address to an interface is a
     * privileged operation no application API exposes.
     */
    SECONDARY_IP(
        "Adresse secondaire",
        "Ajoute une vraie adresse sur une interface du téléphone. Nécessite root.",
    ),
}

/**
 * A way of reaching hosts, named and reusable.
 *
 * Tunnels used to be configured inside each host, which meant two servers behind the same VPN each
 * opened their own tunnel to it. Naming the network and pointing hosts at it lets them share one —
 * and lets a configuration be corrected in one place.
 */
data class NetworkConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: NetworkType,
    /**
     * Each type keeps its own field rather than sharing one, so switching type does not discard a
     * configuration and cannot show one type's secrets in another's unmasked text area.
     */
    val wireGuardConfig: String = "",
    val sshTunnelConfig: String = "",
    /** Interface the secondary address is added to, such as `wlan0`. */
    val interfaceName: String = "",
    /** The address to add, in CIDR form. */
    val secondaryAddress: String = "",
) {
    /** Whether this configuration holds enough to attempt a connection. */
    val isUsable: Boolean
        get() = when (type) {
            NetworkType.WIREGUARD -> wireGuardConfig.isNotBlank()
            NetworkType.SSH_TUNNEL -> sshTunnelConfig.isNotBlank()
            NetworkType.SECONDARY_IP -> interfaceName.isNotBlank() && secondaryAddress.isNotBlank()
        }
}
