package net.raphaelgf11.ilo3manager.vpn

import net.raphaelgf11.ilo3manager.data.NetworkConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Puts a secondary address on one of the phone's interfaces, and takes it off again.
 *
 * Runs `ip` through `su`. No Android API configures addresses, so root is the only way — and the
 * reason this network type is worth the trouble at all is that the resulting address is real: the
 * iLO can route back to it, which a userspace tunnel's address only manages while the tunnel is up
 * and the app is holding it.
 */
object SecondaryAddressManager {

    /** Long enough for the superuser prompt to be read and answered by a human. */
    private const val ROOT_PROMPT_TIMEOUT_SECONDS = 60L

    /** A root shell's reply, joined, with its exit status. */
    private data class ShellResult(val output: String, val exitCode: Int)

    private fun runAsRoot(command: String): ShellResult = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        // Drained rather than closed: a command writing into a closed pipe dies on SIGPIPE, which
        // would read back as a failure however well it ran.
        val output = process.inputStream.bufferedReader().use { it.readText() }
        // Generous on purpose: the first call raises the superuser prompt, and the clock is then
        // running on a person reading a dialog. Ten seconds killed the request while the prompt
        // was still on screen, which read back as "root refusé" on a phone that would have said
        // yes. Later calls answer immediately, so this ceiling costs nothing once granted.
        if (!process.waitFor(ROOT_PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            ShellResult(
                "Aucune réponse au bout de $ROOT_PROMPT_TIMEOUT_SECONDS secondes : la demande " +
                    "de superutilisateur est peut-être restée sans réponse.",
                -1,
            )
        } else {
            ShellResult(output.trim(), process.exitValue())
        }
    } catch (e: Exception) {
        // No su binary at all lands here, which is the ordinary case on an unrooted phone.
        ShellResult(e.message ?: "su indisponible", -1)
    }

    /** Whether a root shell answers at all; false on every unrooted phone. */
    fun isRootAvailable(): Boolean {
        val result = runAsRoot("id")
        return result.exitCode == 0 && result.output.contains("uid=0")
    }

    /**
     * Makes sure the network's address is on its interface, adding it if not.
     *
     * Idempotent, because it runs on every connection: `ip` treats adding an existing address as an
     * error, and that particular error is the state we wanted.
     */
    fun ensureApplied(network: NetworkConfig): Result<Unit> {
        val command = secondaryAddressAddCommand(network.interfaceName, network.secondaryAddress)
            ?: return Result.failure(
                IOException(
                    "Interface ou adresse invalide : « ${network.interfaceName} » et " +
                        "« ${network.secondaryAddress} ». L'adresse doit être en notation CIDR, " +
                        "par exemple 192.168.1.9/24.",
                ),
            )

        // Asked of the JVM rather than of a root shell. An earlier version ran `ip addr show`
        // through su to decide whether the add was needed, which cost a second superuser prompt
        // for one action — the check was as privileged as the thing it was meant to avoid.
        if (interfaceHasAddress(network.interfaceName, network.secondaryAddress)) {
            return Result.success(Unit)
        }

        val result = runAsRoot(command)
        if (result.exitCode == 0 || addFailureIsAlreadyPresent(result.output)) {
            return Result.success(Unit)
        }
        return Result.failure(
            IOException(
                "Impossible d'ajouter ${network.secondaryAddress} sur " +
                    "${network.interfaceName} : ${result.output.ifBlank { "root refusé" }}",
            ),
        )
    }

    /** Removes the address, for a network being deleted or switched off. */
    fun remove(network: NetworkConfig): Result<Unit> {
        val command = secondaryAddressRemoveCommand(network.interfaceName, network.secondaryAddress)
            ?: return Result.success(Unit)
        val result = runAsRoot(command)
        return if (result.exitCode == 0) Result.success(Unit)
        else Result.failure(IOException(result.output.ifBlank { "Suppression refusée." }))
    }

    /**
     * Whether the interface already carries the address, asked without any privilege.
     *
     * Compares the prefix too: the same address with another prefix routes differently, which is
     * the only reason to configure one.
     */
    fun interfaceHasAddress(interfaceName: String, cidr: String): Boolean = runCatching {
        val wanted = cidr.substringBefore('/')
        val prefix = cidr.substringAfter('/', "").toIntOrNull() ?: return false
        java.net.NetworkInterface.getByName(interfaceName)
            ?.interfaceAddresses
            ?.any { it.address.hostAddress == wanted && it.networkPrefixLength.toInt() == prefix }
            ?: false
    }.getOrDefault(false)

    /** The interfaces the phone currently has, for the setup screen to offer. */
    fun interfaceNames(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .map { it.name }
            .filter { isValidInterfaceName(it) }
    }.getOrDefault(emptyList())
}
