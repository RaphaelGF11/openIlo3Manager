package net.raphaelgf11.ilo3manager.vpn

/**
 * Adding a real address to one of the phone's interfaces.
 *
 * The one option that gives the phone an address the network can route back to, which is what an
 * iLO needs to send its traps here. It costs root: no Android API exposes address configuration,
 * so this goes through `ip` as the superuser.
 *
 * The command-building half lives here, apart from the running of it, because it is where the
 * safety is decided — the interface name and the address come from a text field, and they are
 * about to be handed to a root shell.
 */

/** Interface names as the kernel allows them, and nothing that a shell would read as syntax. */
private val INTERFACE_PATTERN = Regex("^[A-Za-z0-9._-]{1,15}$")

/** Dotted quad with a prefix length, each octet in range. */
private val CIDR_PATTERN = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})/(\\d{1,2})$")

/**
 * Whether [name] can be handed to a root shell as an interface.
 *
 * Rejecting rather than quoting: the value reaches `su -c`, where one unescaped character is the
 * difference between adding an address and running whatever follows a semicolon.
 */
fun isValidInterfaceName(name: String): Boolean = INTERFACE_PATTERN.matches(name)

/** Whether [cidr] is a well-formed IPv4 address with a prefix, such as `192.168.1.9/24`. */
fun isValidCidr(cidr: String): Boolean {
    val match = CIDR_PATTERN.matchEntire(cidr) ?: return false
    val octets = (1..4).map { match.groupValues[it].toInt() }
    val prefix = match.groupValues[5].toInt()
    return octets.all { it in 0..255 } && prefix in 0..32
}

/**
 * The command that adds the address, or null when either half is unsafe to send.
 *
 * Returning null rather than throwing: the caller has a user-facing error to show either way, and
 * an unsafe value is a refusal rather than a fault.
 */
fun secondaryAddressAddCommand(interfaceName: String, cidr: String): String? {
    if (!isValidInterfaceName(interfaceName) || !isValidCidr(cidr)) return null
    return "ip addr add $cidr dev $interfaceName"
}

/** The command that takes it back off again. */
fun secondaryAddressRemoveCommand(interfaceName: String, cidr: String): String? {
    if (!isValidInterfaceName(interfaceName) || !isValidCidr(cidr)) return null
    return "ip addr del $cidr dev $interfaceName"
}

/**
 * Whether `ip addr show` already reports [cidr] on the interface.
 *
 * Compared on the address and prefix together, so an address present with a different prefix reads
 * as absent — it would route differently, which is the whole point of configuring one.
 */
fun outputHasAddress(ipAddrOutput: String, cidr: String): Boolean =
    ipAddrOutput.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("inet ") }
        .map { it.removePrefix("inet ").substringBefore(' ') }
        .any { it == cidr }

/**
 * Whether the failure of `ip addr add` means the address was already there.
 *
 * Adding an address that exists is success as far as this app is concerned, but `ip` calls it an
 * error, so the two have to be told apart from its message.
 */
fun addFailureIsAlreadyPresent(output: String): Boolean =
    output.contains("File exists", ignoreCase = true) ||
        output.contains("RTNETLINK answers: File exists", ignoreCase = true)
