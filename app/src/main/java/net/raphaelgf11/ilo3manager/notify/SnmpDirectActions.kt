package net.raphaelgf11.ilo3manager.notify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.data.NetworkConfig
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ssh.IloCliClient
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Points the iLO's SNMP traps at this phone, and checks it can actually get here.
 *
 * Both run over the CLI rather than the web API: `set /map1/snmp1` and `oemhp_ping` are verbs this
 * firmware really exposes, which was checked against the machine. Each action opens one session and
 * closes it — the iLO 3 allows very few, and they are shared with the web interface, so leaking one
 * locks the user out of both.
 */
object SnmpDirectActions {

    private const val SNMP_NODE = "/map1/snmp1"

    /**
     * The phone's own IPv4 addresses, loopback excluded.
     *
     * Only used when the host is reached directly. Behind a tunnel the address that matters is the
     * one inside it, which comes from the network's configuration instead.
     */
    fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /** What the iLO currently holds, so the user can choose a slot knowing what is in each. */
    data class SnmpState(
        /** The address this phone would be reachable at. */
        val destination: String,
        /** Current value of each slot, in order; see [SNMP_DESTINATION_SLOTS]. */
        val slots: List<String>,
        val alertsEnabled: Boolean,
        /** The slot the app would pick on its own, or null when none is free. */
        val recommended: Int?,
    )

    /** Reads the destinations so they can be shown before anything is overwritten. */
    suspend fun readSnmpState(host: SshHost, network: NetworkConfig?): Result<SnmpState> =
        withSession(host, network) { cli, destination ->
            val output = cli.runCommand("show $SNMP_NODE")
            val slots = parseSnmpDestinations(output)
            Result.success(
                SnmpState(
                    destination = destination,
                    slots = slots,
                    alertsEnabled = parseIloAlertsEnabled(output),
                    recommended = when (val choice = chooseSnmpSlot(slots, destination)) {
                        is SnmpSlotChoice.Free -> choice.slot
                        is SnmpSlotChoice.AlreadySet -> choice.slot
                        SnmpSlotChoice.AllTaken -> null
                    },
                ),
            )
        }

    /**
     * Writes this phone into [slot], whatever that slot held.
     *
     * The slot is the caller's decision rather than this function's: overwriting a destination is
     * sometimes exactly what is wanted, but only the user knows whether the one being replaced
     * still matters, so it is chosen in front of the current values rather than guessed here.
     */
    suspend fun writeDestination(
        host: SshHost,
        network: NetworkConfig?,
        slot: Int,
    ): Result<String> = withSession(host, network) { cli, destination ->
        require(slot in 1..SNMP_DESTINATION_SLOTS) { "Destination $slot inexistante." }

        val before = cli.runCommand("show $SNMP_NODE")
        val previous = parseSnmpDestinations(before).getOrNull(slot - 1).orEmpty()
        cli.runCommand("set $SNMP_NODE accessinfo$slot=$destination")
        val alreadyEnabled = ensureAlertsEnabled(cli, before)

        // Read back rather than trust the write: the CLI reports status=0 for a set whose value
        // the firmware then rejects or truncates.
        val after = parseSnmpDestinations(cli.runCommand("show $SNMP_NODE"))
        if (after.getOrNull(slot - 1)?.trim() != destination) {
            return@withSession Result.failure(
                IllegalStateException(
                    "L'iLO a accepté la commande mais la destination $slot ne montre pas " +
                        "$destination. Rien n'a été configuré.",
                ),
            )
        }

        Result.success(
            buildString {
                append("L'iLO enverra ses traps à $destination (destination $slot).")
                if (previous.isNotBlank() && previous != "0" && previous != destination) {
                    append(" L'ancienne valeur $previous a été remplacée.")
                }
                if (!alreadyEnabled) append(" Les alertes iLO ont été activées.")
            },
        )
    }

    /**
     * Asks the iLO whether it can reach the phone.
     *
     * This is the half of the chain that actually breaks, and the only half this firmware lets us
     * test: its CLI has no equivalent of the web interface's "Send test alert", so a trap being
     * accepted by the phone's listener is not something we can provoke from here. A successful ping
     * proves the route exists in the right direction; it does not prove UDP 162 is open along it.
     */
    suspend fun testReachability(host: SshHost, network: NetworkConfig?): Result<String> =
        withSession(host, network) { cli, destination ->
            val output = cli.runCommand("oemhp_ping $destination")
            if (parsePingSucceeded(output)) {
                Result.success(
                    "L'iLO joint le téléphone à $destination. La route existe dans le bon sens ; " +
                        "il reste que le port UDP 162 ne soit pas filtré en chemin.",
                )
            } else {
                Result.failure(
                    IllegalStateException(
                        "L'iLO ne joint pas $destination. Aucune trap ne peut arriver tant que " +
                            "c'est le cas — vérifiez la route de retour vers ce réseau.",
                    ),
                )
            }
        }

    /** Returns whether alerts were already on, enabling them when they were not. */
    private suspend fun ensureAlertsEnabled(cli: IloCliClient, showOutput: String): Boolean {
        val enabled = parseIloAlertsEnabled(showOutput)
        // Without this the destinations are set and nothing is ever sent to them.
        if (!enabled) cli.runCommand("set $SNMP_NODE oemhp_iloalert=yes")
        return enabled
    }

    private suspend fun <T> withSession(
        host: SshHost,
        network: NetworkConfig?,
        block: suspend (IloCliClient, String) -> Result<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
        val destination = trapDestinationFor(network, ::localIpv4Addresses)
            .getOrElse { return@withContext Result.failure(it) }

        val cli = IloCliClient()
        try {
            cli.connect(host)
            block(cli, destination)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // The session must go back even when the command threw: this BMC has very few, and they
            // are shared with the web interface.
            runCatching { cli.disconnect() }
        }
    }
}
