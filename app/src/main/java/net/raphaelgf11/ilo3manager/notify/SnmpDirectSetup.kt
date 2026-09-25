package net.raphaelgf11.ilo3manager.notify

import net.raphaelgf11.ilo3manager.data.NetworkConfig
import net.raphaelgf11.ilo3manager.data.NetworkType
import net.raphaelgf11.ilo3manager.vpn.WireGuardConfigParser

/**
 * Configuring the iLO's trap destinations, and checking it can reach us.
 *
 * All of it is text in and text out, deliberately: the commands run against a BMC that allows very
 * few sessions, so the rules that decide what to send — above all which slot may be overwritten —
 * have to be settled without a device.
 */

/** The three destination slots the iLO exposes as `accessinfo1..3` on `/map1/snmp1`. */
const val SNMP_DESTINATION_SLOTS = 3

/** What to do with a destination the user wants the iLO to send traps to. */
sealed interface SnmpSlotChoice {
    /** Free slot, safe to write. Slots are 1-based, as the iLO names them. */
    data class Free(val slot: Int) : SnmpSlotChoice

    /** The destination is already configured; writing again would change nothing. */
    data class AlreadySet(val slot: Int) : SnmpSlotChoice

    /** Every slot holds some other destination. Overwriting one would silently break it. */
    data object AllTaken : SnmpSlotChoice
}

/**
 * An unset slot reads as `0` rather than as an empty value on this firmware.
 *
 * `0.0.0.0` is treated the same way: it addresses nothing, so a slot holding it is not a
 * destination anybody is relying on.
 */
private fun isFree(value: String): Boolean =
    value.isBlank() || value == "0" || value == "0.0.0.0"

/**
 * Picks the slot to write [destination] into.
 *
 * Never overwrites a destination already in use: the other slots may point at a monitoring system
 * the user runs, and breaking that to make room would be both invisible and hard to undo.
 */
fun chooseSnmpSlot(current: List<String>, destination: String): SnmpSlotChoice {
    val existing = current.indexOfFirst { it.trim() == destination }
    if (existing >= 0) return SnmpSlotChoice.AlreadySet(existing + 1)
    val free = current.indexOfFirst { isFree(it.trim()) }
    if (free >= 0) return SnmpSlotChoice.Free(free + 1)
    return SnmpSlotChoice.AllTaken
}

/**
 * Reads the destination slots out of `show /map1/snmp1`.
 *
 * Always returns [SNMP_DESTINATION_SLOTS] entries, blank where the property was absent, so callers
 * index by slot without having to guard against a short list.
 */
fun parseSnmpDestinations(output: String): List<String> {
    val values = (1..SNMP_DESTINATION_SLOTS).map { slot ->
        val prefix = "accessinfo$slot="
        output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            .orEmpty()
    }
    return values
}

/** Whether iLO-generated alerts are enabled at all; without this no trap is ever sent. */
fun parseIloAlertsEnabled(output: String): Boolean =
    output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("oemhp_iloalert=") }
        ?.removePrefix("oemhp_iloalert=")
        ?.trim()
        .equals("yes", ignoreCase = true)

/**
 * Whether `oemhp_ping` reached the address.
 *
 * The iLO prints a summary line, but it also prints `status=0` on a *failed* ping — the command
 * itself succeeded, only its subject was unreachable. Reading the status would therefore report
 * every ping as a success.
 */
fun parsePingSucceeded(output: String): Boolean =
    output.contains("ping was successful", ignoreCase = true)

/** Why the phone cannot be a trap destination, or null when it can. */
fun trapDestinationBlocker(network: NetworkConfig?): String? = when (network?.type) {
    NetworkType.SSH_TUNNEL ->
        "Ce serveur passe par un rebond SSH, qui ne transporte que du TCP. Les traps SNMP sont " +
            "en UDP : elles ne peuvent pas arriver par ce chemin."
    else -> null
}

/**
 * The address the iLO should send traps to, as it would see the phone.
 *
 * Behind WireGuard that is the phone's address inside the tunnel, not its address on the Wi-Fi:
 * the iLO answers into the tunnel, and the Wi-Fi address means nothing on the far side of it.
 *
 * [localAddresses] supplies the phone's own addresses, injected so this can be exercised without a
 * network stack.
 */
fun trapDestinationFor(
    network: NetworkConfig?,
    localAddresses: () -> List<String>,
): Result<String> {
    trapDestinationBlocker(network)?.let { return Result.failure(IllegalStateException(it)) }

    return when (network?.type) {
        NetworkType.WIREGUARD -> runCatching {
            val parsed = WireGuardConfigParser.parse(network.wireGuardConfig)
            parsed.addresses.firstOrNull()?.substringBefore('/')?.takeIf { it.isNotBlank() }
                ?: error("La configuration WireGuard ne déclare aucune adresse locale.")
        }
        NetworkType.SECONDARY_IP -> {
            val address = network.secondaryAddress.substringBefore('/').trim()
            if (address.isEmpty()) {
                Result.failure(IllegalStateException("Aucune adresse secondaire n'est renseignée."))
            } else {
                Result.success(address)
            }
        }
        // Direct, or a type with no address of its own: whatever the phone holds on the network it
        // shares with the server.
        else -> localAddresses().firstOrNull()
            ?.let { Result.success(it) }
            ?: Result.failure(
                IllegalStateException("Aucune adresse IPv4 trouvée sur le téléphone."),
            )
    }
}
