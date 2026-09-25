package net.raphaelgf11.ilo3manager.notify

/**
 * Decodes SNMP trap datagrams.
 *
 * These arrive unsolicited from the network, from whatever can reach the listening port, so this
 * reads defensively throughout: every length is checked against what is left, nothing is allocated
 * from a length field, and a malformed packet returns null rather than throwing. A trap that cannot
 * be parsed is a trap to drop, not a crash in a background service.
 *
 * Covers SNMPv1 (RFC 1157) and v2c (RFC 3416) traps, which is what an iLO 3 emits — it has no v3.
 */

/** One `oid = value` pair out of a trap's variable bindings. */
data class VarBind(val oid: String, val value: String)

/** A trap, in the terms the app needs to describe it to the user. */
data class SnmpTrap(
    /** 0 for v1, 1 for v2c, as the version field encodes them. */
    val version: Int,
    val community: String,
    /** v1 only: the OID identifying what sent this. Blank for v2c. */
    val enterprise: String = "",
    /** v1 only: the agent's own address, which can differ from the sender's. */
    val agentAddress: String = "",
    /** v1 only: 0..6, where 6 means "see [specificTrap]". */
    val genericTrap: Int = -1,
    /** v1 only: the vendor's own trap number. */
    val specificTrap: Int = -1,
    /** Hundredths of a second since the agent came up. */
    val uptimeTicks: Long = 0,
    val varbinds: List<VarBind> = emptyList(),
) {
    /** True for v2c, whose trap identity lives in a varbind rather than in the PDU header. */
    val isV2c: Boolean get() = version == 1

    /**
     * The OID naming what happened, whichever version carried it.
     *
     * v1 spells it out in the PDU; v2c puts it in the second varbind, by convention
     * `1.3.6.1.6.3.1.1.4.1.0`. Callers that want to recognise a trap should not have to know which.
     */
    val trapOid: String
        get() = if (isV2c) {
            varbinds.firstOrNull { it.oid == SNMP_TRAP_OID }?.value.orEmpty()
        } else {
            enterprise
        }
}

/** Where v2c puts the trap's identity. */
const val SNMP_TRAP_OID = "1.3.6.1.6.3.1.1.4.1.0"

/** The generic traps every SNMP agent can send, by their number in the PDU. */
val GENERIC_TRAP_LABELS = mapOf(
    0 to "Démarrage à froid",
    1 to "Démarrage à chaud",
    2 to "Lien réseau perdu",
    3 to "Lien réseau rétabli",
    4 to "Échec d'authentification",
    5 to "Voisin EGP perdu",
    6 to "Alerte spécifique au constructeur",
)

/** Largest datagram worth parsing; anything beyond is not a trap this app should be reading. */
private const val MAX_TRAP_BYTES = 8 * 1024

// BER tags, only those a trap can carry.
private const val TAG_INTEGER = 0x02
private const val TAG_OCTET_STRING = 0x04
private const val TAG_NULL = 0x05
private const val TAG_OID = 0x06
private const val TAG_SEQUENCE = 0x30
private const val TAG_IP_ADDRESS = 0x40
private const val TAG_COUNTER32 = 0x41
private const val TAG_GAUGE32 = 0x42
private const val TAG_TIMETICKS = 0x43
private const val TAG_COUNTER64 = 0x46
private const val TAG_TRAP_V1 = 0xA4
private const val TAG_TRAP_V2 = 0xA7

/**
 * Reads BER type-length-value fields, refusing to step outside the buffer.
 *
 * Every accessor checks before it reads: a length field claiming more than the datagram holds is
 * the first thing a malformed or hostile packet would carry.
 */
private class BerReader(private val bytes: ByteArray, var pos: Int, private val end: Int) {

    val remaining: Int get() = end - pos

    fun readTag(): Int? = if (remaining < 1) null else bytes[pos++].toInt() and 0xFF

    /** Returns the length, or null when it is absent, over-long, or longer than what is left. */
    fun readLength(): Int? {
        if (remaining < 1) return null
        val first = bytes[pos++].toInt() and 0xFF
        if (first and 0x80 == 0) return first.takeIf { it <= remaining }

        val count = first and 0x7F
        // Beyond four bytes the value could not address anything this app accepts, and the shift
        // below would overflow.
        if (count == 0 || count > 4 || remaining < count) return null
        var value = 0
        repeat(count) { value = (value shl 8) or (bytes[pos++].toInt() and 0xFF) }
        return value.takeIf { it in 0..remaining }
    }

    fun skip(length: Int): Boolean {
        if (length > remaining) return false
        pos += length
        return true
    }

    fun readLong(length: Int): Long? {
        if (length <= 0 || length > 8 || length > remaining) return null
        var value = if (bytes[pos].toInt() and 0x80 != 0) -1L else 0L
        repeat(length) { value = (value shl 8) or (bytes[pos++].toLong() and 0xFF) }
        return value
    }

    fun readString(length: Int): String? {
        if (length < 0 || length > remaining) return null
        val text = String(bytes, pos, length, Charsets.UTF_8)
        pos += length
        return text
    }

    /** Dotted-decimal, as SNMP addresses are always four bytes. */
    fun readIpAddress(length: Int): String? {
        if (length != 4 || length > remaining) return null
        val text = (0 until 4).joinToString(".") { (bytes[pos + it].toInt() and 0xFF).toString() }
        pos += 4
        return text
    }

    /**
     * Decodes an object identifier.
     *
     * The first byte packs the first two arcs, and the rest are base-128 with a continuation bit —
     * which is where a truncated packet ends mid-arc, so the accumulator is bounded.
     */
    fun readOid(length: Int): String? {
        if (length < 1 || length > remaining) return null
        val stop = pos + length
        val first = bytes[pos++].toInt() and 0xFF
        val arcs = mutableListOf(first / 40, first % 40)
        var value = 0L
        while (pos < stop) {
            val byte = bytes[pos++].toInt() and 0xFF
            // Guard the accumulator: a run of continuation bytes must not overflow.
            if (value > (Long.MAX_VALUE shr 7)) return null
            value = (value shl 7) or (byte and 0x7F).toLong()
            if (byte and 0x80 == 0) {
                arcs.add(value.toInt())
                value = 0
            }
        }
        return arcs.joinToString(".")
    }
}

/**
 * Decodes a trap, or returns null when the bytes are not one.
 *
 * Null covers every failure alike — truncated, not a trap, a version this app does not read — on
 * purpose: nothing downstream can do anything different about them, and a caller tempted to report
 * the difference would be reporting on traffic it cannot trust.
 */
fun decodeSnmpTrap(datagram: ByteArray, length: Int = datagram.size): SnmpTrap? {
    if (length !in 2..MAX_TRAP_BYTES || length > datagram.size) return null
    val reader = BerReader(datagram, 0, length)

    if (reader.readTag() != TAG_SEQUENCE) return null
    val messageLength = reader.readLength() ?: return null
    val messageEnd = reader.pos + messageLength

    if (reader.readTag() != TAG_INTEGER) return null
    val version = reader.readLength()?.let { reader.readLong(it) }?.toInt() ?: return null
    // v3 carries its own security layer and nothing below would decode it.
    if (version != 0 && version != 1) return null

    if (reader.readTag() != TAG_OCTET_STRING) return null
    val community = reader.readLength()?.let { reader.readString(it) } ?: return null

    val pduTag = reader.readTag() ?: return null
    val pduLength = reader.readLength() ?: return null
    if (reader.pos + pduLength > messageEnd) return null

    return when (pduTag) {
        TAG_TRAP_V1 -> decodeV1(reader, version, community)
        TAG_TRAP_V2 -> decodeV2(reader, version, community)
        else -> null
    }
}

private fun decodeV1(reader: BerReader, version: Int, community: String): SnmpTrap? {
    if (reader.readTag() != TAG_OID) return null
    val enterprise = reader.readLength()?.let { reader.readOid(it) } ?: return null

    if (reader.readTag() != TAG_IP_ADDRESS) return null
    val agent = reader.readLength()?.let { reader.readIpAddress(it) } ?: return null

    if (reader.readTag() != TAG_INTEGER) return null
    val generic = reader.readLength()?.let { reader.readLong(it) }?.toInt() ?: return null

    if (reader.readTag() != TAG_INTEGER) return null
    val specific = reader.readLength()?.let { reader.readLong(it) }?.toInt() ?: return null

    if (reader.readTag() != TAG_TIMETICKS) return null
    val uptime = reader.readLength()?.let { reader.readLong(it) } ?: return null

    return SnmpTrap(
        version = version,
        community = community,
        enterprise = enterprise,
        agentAddress = agent,
        genericTrap = generic,
        specificTrap = specific,
        uptimeTicks = uptime,
        varbinds = readVarBinds(reader),
    )
}

private fun decodeV2(reader: BerReader, version: Int, community: String): SnmpTrap? {
    // request-id, error-status and error-index, none of which a trap uses.
    repeat(3) {
        if (reader.readTag() != TAG_INTEGER) return null
        val length = reader.readLength() ?: return null
        if (!reader.skip(length)) return null
    }
    val varbinds = readVarBinds(reader)
    val uptime = varbinds.firstOrNull { it.oid == "1.3.6.1.2.1.1.3.0" }?.value?.toLongOrNull() ?: 0

    return SnmpTrap(
        version = version,
        community = community,
        uptimeTicks = uptime,
        varbinds = varbinds,
    )
}

/**
 * Reads the variable bindings, stopping at the first one that does not parse.
 *
 * Keeping what was read rather than discarding the trap: the bindings are descriptive, and a trap
 * whose tail is mangled still says that something happened.
 */
private fun readVarBinds(reader: BerReader): List<VarBind> {
    if (reader.readTag() != TAG_SEQUENCE) return emptyList()
    val listLength = reader.readLength() ?: return emptyList()
    val listEnd = reader.pos + listLength
    if (listEnd > reader.pos + reader.remaining) return emptyList()

    val binds = mutableListOf<VarBind>()
    while (reader.pos < listEnd) {
        if (reader.readTag() != TAG_SEQUENCE) break
        val bindLength = reader.readLength() ?: break
        val bindEnd = reader.pos + bindLength

        if (reader.readTag() != TAG_OID) break
        val oid = reader.readLength()?.let { reader.readOid(it) } ?: break

        val valueTag = reader.readTag() ?: break
        val valueLength = reader.readLength() ?: break
        val value = when (valueTag) {
            TAG_INTEGER, TAG_COUNTER32, TAG_GAUGE32, TAG_TIMETICKS, TAG_COUNTER64 ->
                reader.readLong(valueLength)?.toString()
            TAG_OCTET_STRING -> reader.readString(valueLength)?.let(::printable)
            TAG_OID -> reader.readOid(valueLength)
            TAG_IP_ADDRESS -> reader.readIpAddress(valueLength)
            TAG_NULL -> "".also { reader.skip(valueLength) }
            else -> null.also { reader.skip(valueLength) }
        } ?: break

        binds.add(VarBind(oid, value))
        if (reader.pos != bindEnd) {
            // Trailing bytes inside a binding mean the length did not describe its contents.
            if (reader.pos > bindEnd || !reader.skip(bindEnd - reader.pos)) break
        }
    }
    return binds
}

/**
 * Renders an octet string for display.
 *
 * Trap payloads are often text, but nothing stops an agent sending binary — and this string ends up
 * in a notification, where control characters would corrupt the line.
 */
private fun printable(raw: String): String {
    val visible = raw.filter { it == '\n' || it == '\t' || !it.isISOControl() }.trim()
    return visible.ifBlank { raw.toByteArray(Charsets.UTF_8).joinToString(" ") { "%02X".format(it) } }
}

/** A trap rendered for a notification: what happened, and what is known about it. */
data class TrapSummary(val title: String, val detail: String)

/**
 * Turns a trap into the two lines a notification shows.
 *
 * The hard part is that this app has no MIB: an iLO names its events by number inside its own
 * enterprise tree, and resolving those to sentences would mean shipping HP's MIBs. So the text is
 * built from what the packet itself carries — the generic type, which is standardised, and the
 * string bindings, which iLO fills with a description of the event. When neither says anything, the
 * numbers are shown rather than a reassuring guess.
 */
fun describeTrap(trap: SnmpTrap, hostName: String): TrapSummary {
    val generic = GENERIC_TRAP_LABELS[trap.genericTrap]
    val title = when {
        hostName.isNotBlank() -> "$hostName : alerte matérielle"
        else -> "Alerte matérielle"
    }

    // iLO puts a readable description in its string bindings; the longest is the most specific.
    val described = trap.varbinds
        .filter { it.value.isNotBlank() && !it.value.all { c -> c.isDigit() || c == '.' } }
        .maxByOrNull { it.value.length }
        ?.value

    val detail = buildString {
        when {
            described != null -> append(described)
            generic != null && trap.genericTrap != 6 -> append(generic)
            trap.isV2c -> append("Trap ${trap.trapOid}")
            else -> append("Alerte ${trap.enterprise} n° ${trap.specificTrap}")
        }
        if (trap.agentAddress.isNotBlank()) append(" — ${trap.agentAddress}")
    }
    return TrapSummary(title, detail)
}
