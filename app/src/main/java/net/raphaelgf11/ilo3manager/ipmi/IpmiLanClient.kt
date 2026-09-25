package net.raphaelgf11.ilo3manager.ipmi

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** RMCP header: version 6, reserved, sequence 0xFF (no RMCP ACK), message class 7 (IPMI). */
private val RMCP_HEADER = byteArrayOf(0x06, 0x00, 0xFF.toByte(), 0x07)

/** Auth Type/Format value marking an IPMI 2.0 (RMCP+) session header. */
private const val RMCP_PLUS_AUTH_TYPE: Byte = 0x06

/** RMCP(4) + auth type(1) + payload type(1) + session id(4) + sequence(4) + payload length(2). */
private const val RMCP_PLUS_HEADER_SIZE = 16

/** IPMI network function codes used here. */
private const val NET_FN_CHASSIS = 0x00
private const val NET_FN_APP = 0x06

/**
 * Privilege level requested when opening the session.
 *
 * Administrator forces the iLO account to hold every privilege, which is more than this app needs:
 * Operator already covers power control and the locator LED, and User is enough to read state.
 * Asking for the least that works avoids granting a management account full rights for a dashboard.
 */
enum class IpmiPrivilege(val level: Int, val label: String) {
    USER(2, "Lecture seule"),
    OPERATOR(3, "Opérateur"),
    ADMINISTRATOR(4, "Administrateur"),
    ;

    /** Name-only lookup (bit 4) combined with the level, as RAKP expects it. */
    val rakpByte: Byte get() = (0x10 or level).toByte()
}

/** Retransmissions before giving up on a datagram. */
private const val RETRY_ATTEMPTS = 3

/**
 * RMCP+ status codes that say the account itself was turned away.
 *
 * Everything else — a busy BMC, an expired session id — can succeed on a second try, so only these
 * are reported as [IpmiRefusedException] and spare the caller from repeating the poll.
 */
private val DEFINITIVE_REFUSALS = setOf(0x09, 0x0A, 0x0C, 0x0D, 0x0F)

enum class ChassisPowerState { ON, OFF, UNKNOWN }

/** Decoded Get Chassis Status reply: power state plus the chassis-level fault indicators. */
data class ChassisStatus(
    val power: ChassisPowerState,
    val powerOverload: Boolean = false,
    val mainPowerFault: Boolean = false,
    val powerControlFault: Boolean = false,
    val driveFault: Boolean = false,
    val coolingFault: Boolean = false,
    /** The blue locator LED on the chassis front and rear. */
    val identifyOn: Boolean = false,
) {
    val hasFault: Boolean
        get() = powerOverload || mainPowerFault || powerControlFault || driveFault || coolingFault

    /**
     * Faults that put the machine itself at risk, as opposed to a degraded but running state: a
     * power fault means the server may stop, whereas a failed drive in an array or one dead fan
     * usually does not.
     */
    val hasCriticalFault: Boolean
        get() = powerOverload || mainPowerFault || powerControlFault
}

enum class ChassisControl(val code: Int) {
    POWER_DOWN(0x00),
    POWER_UP(0x01),
    POWER_CYCLE(0x02),
    HARD_RESET(0x03),
    SOFT_SHUTDOWN(0x05),
}

/**
 * Minimal IPMI v2.0 (RMCP+) LAN client: session establishment via RAKP-HMAC-SHA1 (cipher suite 3:
 * HMAC-SHA1-96 integrity, AES-CBC-128 confidentiality), used only for the two commands that make
 * IPMI worth using here — Get Chassis Status and Chassis Control — since power state/actions are
 * what the SSH CLI is slowest at. Everything else (hardware/health detail) stays on SSH.
 *
 * This is not a general-purpose IPMI stack: no cipher-suite negotiation, no fallback to weaker
 * auth types, no retransmission beyond a single retry. iLO3 does not enable IPMI/DCMI over LAN by
 * default, so a host must have it turned on for this to work at all.
 */
class IpmiLanClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val privilege: IpmiPrivilege = IpmiPrivilege.ADMINISTRATOR,
) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null

    private var managedSystemSessionId: Int = 0
    private var consoleSessionId: Int = 0
    private var sik: ByteArray = ByteArray(0)
    private var k1: ByteArray = ByteArray(0)
    private var k2: ByteArray = ByteArray(0)
    private var outboundSeq: Int = 1
    private var rqSeq: Int = 0

    /**
     * Distinguishes one session-setup message from the next.
     *
     * UDP has no request/response pairing, so a retransmission can leave the BMC answering twice;
     * without a tag the late reply to the previous message is read as the answer to the current
     * one, and the mismatched payload then fails far from the cause. Only shows up under latency,
     * which is why it appeared through a tunnel and never on a local network.
     */
    private var messageTag: Int = 0

    private val random = SecureRandom()

    /**
     * The highest privilege the BMC will grant this account, read from the Open Session Response.
     *
     * The BMC answers with a ceiling of its own rather than echoing what was asked: an account
     * without the matching iLO privileges is held lower, silently. Knowing the ceiling is what lets
     * a host be configured for the level it can actually reach instead of one that fails later, on
     * a command rather than at login.
     */
    var grantedPrivilege: IpmiPrivilege? = null
        private set

    fun open() {
        address = InetAddress.getByName(host)
        socket = DatagramSocket().apply { soTimeout = 2_500 }

        consoleSessionId = random.nextInt().let { if (it == 0) 1 else it }
        val consoleRandom = ByteArray(16).also { random.nextBytes(it) }

        val openTag = nextTag()
        val openResp = payloadOf(exchangePreSession(buildOpenSessionRequest(openTag), openTag))
        // Open Session Response payload: tag(1) status(1) privilege(1) reserved(1)
        // console session id(4) managed system session id(4) ...
        checkRmcpStatus(openResp, "ouverture de session")
        managedSystemSessionId = readIntLe(openResp, 8)
        // Byte 2 is the ceiling the BMC grants, which can be lower than the one requested.
        grantedPrivilege = IpmiPrivilege.entries.firstOrNull { it.level == (openResp[2].toInt() and 0x0F) }

        val rakp1Tag = nextTag()
        val rakp1 = buildRakpMessage1(consoleRandom, rakp1Tag)
        val rakp2 = payloadOf(exchangePreSession(rakp1, rakp1Tag))
        // RAKP2 payload: tag(1) status(1) reserved(2) console session id(4)
        // BMC random(16) BMC GUID(16) key exchange auth code(20).
        // A refusal carries only the first eight bytes, so the status has to be read before
        // anything is sliced out of it — otherwise a clear rejection surfaces as an opaque
        // "toIndex is greater than size" from the array copy below.
        checkRmcpStatus(rakp2, "authentification")
        if (rakp2.size < 40) {
            throw IOException("Réponse RAKP2 tronquée (${rakp2.size} octets) : l'iLO a refusé la session.")
        }
        val bmcRandom = rakp2.copyOfRange(8, 24)
        val bmcGuid = rakp2.copyOfRange(24, 40)

        val kUid = kuid()
        sik = hmacSha1(kUid, concat(consoleRandom, bmcRandom, byteArrayOf(privilege.rakpByte, username.length.toByte()), username.toByteArray(Charsets.US_ASCII)))
        k1 = hmacSha1(sik, ByteArray(20) { 0x01 })
        k2 = hmacSha1(sik, ByteArray(20) { 0x02 })

        val rakp2AuthData = concat(
            intLe(consoleSessionId), intLe(managedSystemSessionId), consoleRandom, bmcRandom, bmcGuid,
            byteArrayOf(privilege.rakpByte, username.length.toByte()), username.toByteArray(Charsets.US_ASCII),
        )
        val expected = hmacSha1(kUid, rakp2AuthData)
        val actual = rakp2.copyOfRange(40, minOf(rakp2.size, 60))
        if (!expected.copyOf(actual.size).contentEquals(actual)) {
            close()
            throw IpmiRefusedException("Authentification IPMI refusée (identifiants incorrects ?)")
        }

        val rakp3AuthData = concat(
            bmcRandom, intLe(consoleSessionId), byteArrayOf(privilege.rakpByte, username.length.toByte()),
            username.toByteArray(Charsets.US_ASCII),
        )
        val rakp3AuthCode = hmacSha1(kUid, rakp3AuthData)
        val rakp3Tag = nextTag()
        exchangePreSession(buildRakpMessage3(rakp3AuthCode, rakp3Tag), rakp3Tag)

        raiseSessionPrivilege()
    }

    /**
     * Fails with the BMC's own reason when a session-setup message reports an error.
     *
     * These replies carry a status byte that says exactly why a session was refused; without
     * reading it the only symptom is a malformed short packet much later.
     */
    private fun checkRmcpStatus(payload: ByteArray, stage: String) {
        if (payload.size < 2) throw IOException("Réponse IPMI vide pendant l'$stage.")
        val status = payload[1].toInt() and 0xFF
        if (status == 0x00) return
        val reason = when (status) {
            0x01 -> "ressources insuffisantes sur l'iLO (trop de sessions IPMI ouvertes ?)"
            0x02 -> "identifiant de session invalide"
            0x08 -> "session inactive"
            0x09, 0x0A -> "niveau de privilège refusé pour cet utilisateur"
            0x0B -> "ressources insuffisantes pour ce niveau de privilège"
            0x0C -> "longueur de nom d'utilisateur invalide"
            0x0D -> "utilisateur inconnu ou non autorisé en IPMI"
            0x0F -> "code d'authentification invalide (mot de passe incorrect ?)"
            0x11 -> "aucune suite de chiffrement commune"
            else -> "code 0x%02x".format(status)
        }
        val message = "L'iLO a refusé l'$stage : $reason."
        // A busy BMC or a stale session id is worth trying again; a rejected account is not.
        throw if (status in DEFINITIVE_REFUSALS) {
            IpmiRefusedException(message)
        } else {
            IOException(message)
        }
    }

    /**
     * Raises the established session to Administrator (Set Session Privilege Level).
     *
     * RAKP only negotiates the privilege *ceiling*; the session itself starts lower, so read-only
     * commands succeed while anything that changes state comes back as 0xD4 "insufficient
     * privilege". That asymmetry is exactly how the missing call showed up: the dashboard could
     * read power state fine but every power action was refused.
     */
    private fun raiseSessionPrivilege() {
        sendCommand(netFn = NET_FN_APP, cmd = 0x3B, data = byteArrayOf(privilege.level.toByte()))
    }

    fun getPowerState(): ChassisPowerState = getChassisStatus().power

    /**
     * Get Chassis Status. Beyond the power state, the reply carries the chassis-level fault bits
     * the front panel summarises — which is what the dashboard's health indicator needs, without
     * having to walk the whole SDR sensor repository.
     */
    fun getChassisStatus(): ChassisStatus {
        val resp = sendCommand(netFn = NET_FN_CHASSIS, cmd = 0x01, data = ByteArray(0))
        if (resp.isEmpty()) return ChassisStatus(ChassisPowerState.UNKNOWN)
        val currentPower = resp[0].toInt() and 0xFF
        val misc = if (resp.size > 2) resp[2].toInt() and 0xFF else 0
        return ChassisStatus(
            power = if (currentPower and 0x01 != 0) ChassisPowerState.ON else ChassisPowerState.OFF,
            powerOverload = currentPower and 0x02 != 0,
            mainPowerFault = currentPower and 0x08 != 0,
            powerControlFault = currentPower and 0x10 != 0,
            driveFault = misc and 0x04 != 0,
            coolingFault = misc and 0x08 != 0,
            // Bits 5:4 hold the identify state: 0 off, 1 on temporarily, 2 on indefinitely.
            identifyOn = (misc shr 4) and 0x03 != 0,
        )
    }

    /**
     * Turns the chassis locator LED on indefinitely or off (Chassis Identify).
     *
     * "On" passes the maximum interval together with the "force identify on" flag, because the
     * plain interval form would switch itself off again after at most 255 seconds.
     */
    fun setIdentify(on: Boolean) {
        val data = if (on) byteArrayOf(0xFF.toByte(), 0x01) else byteArrayOf(0x00)
        sendCommand(netFn = NET_FN_CHASSIS, cmd = 0x04, data = data)
    }

    fun chassisControl(action: ChassisControl) {
        sendCommand(netFn = NET_FN_CHASSIS, cmd = 0x02, data = byteArrayOf(action.code.toByte()))
    }

    /** Issues an arbitrary command; used by [IpmiSensorReader] for the storage and sensor net functions. */
    fun rawCommand(netFn: Int, cmd: Int, data: ByteArray): ByteArray = sendCommand(netFn, cmd, data)

    fun close() {
        runCatching {
            if (managedSystemSessionId != 0) {
                sendCommand(netFn = NET_FN_APP, cmd = 0x3C, data = intLe(managedSystemSessionId), expectResponse = false)
            }
        }
        socket?.close()
        socket = null
    }

    // ---- IPMI message send/receive over an established session ----

    private fun sendCommand(netFn: Int, cmd: Int, data: ByteArray, expectResponse: Boolean = true): ByteArray {
        val rsAddr = 0x20
        val netFnLun = (netFn shl 2)
        val checksum1 = twosComplementChecksum(byteArrayOf(rsAddr.toByte(), netFnLun.toByte()))
        val rqAddr = 0x81
        val seq = (rqSeq++ and 0x3F)
        val rqSeqLun = (seq shl 2)
        val body = byteArrayOf(rqAddr.toByte(), rqSeqLun.toByte(), cmd.toByte()) + data
        val checksum2 = twosComplementChecksum(body)
        val ipmiMessage = byteArrayOf(rsAddr.toByte(), netFnLun.toByte(), checksum1) + body + byteArrayOf(checksum2)

        val packet = buildSessionPacket(ipmiMessage)
        if (!expectResponse) {
            send(packet)
            return ByteArray(0)
        }
        return parseIpmiResponsePayload(sendAndReceive(packet))
    }

    private fun buildSessionPacket(ipmiMessage: ByteArray): ByteArray {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k2.copyOf(16), "AES"), IvParameterSpec(iv))
        val encrypted = iv + cipher.doFinal(applyConfidentialityPad(ipmiMessage))

        val header = ByteBuffer.allocate(1 + 1 + 4 + 4 + 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(RMCP_PLUS_AUTH_TYPE)
            put(0xC0.toByte()) // payload type 0x00 (IPMI msg) with encrypted+authenticated bits set
            putInt(managedSystemSessionId)
            putInt(outboundSeq)
            putShort(encrypted.size.toShort())
        }.array()

        val unpadded = header + encrypted
        val padLength = (4 - (unpadded.size + 2) % 4) % 4
        val pad = ByteArray(padLength) { 0xFF.toByte() }
        val trailer = pad + byteArrayOf(padLength.toByte(), 0x07.toByte())
        val authCode = hmacSha1(k1, unpadded + trailer).copyOf(12)

        outboundSeq++
        return RMCP_HEADER + unpadded + trailer + authCode
    }

    private fun parseIpmiResponsePayload(packet: ByteArray): ByteArray {
        val encLength = readShortLe(packet, RMCP_PLUS_HEADER_SIZE - 2)
        val encStart = RMCP_PLUS_HEADER_SIZE
        val iv = packet.copyOfRange(encStart, encStart + 16)
        val ciphertext = packet.copyOfRange(encStart + 16, encStart + encLength)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k2.copyOf(16), "AES"), IvParameterSpec(iv))
        val ipmiMessage = stripConfidentialityPad(cipher.doFinal(ciphertext))
        // ipmiMessage: rqAddr, netFnLun, checksum1, rsAddr, rsSeqLun, cmd, completionCode, data..., checksum2
        if (ipmiMessage.size < 8) throw IOException("Réponse IPMI trop courte")
        val completionCode = ipmiMessage[6].toInt() and 0xFF
        if (completionCode != 0x00) {
            throw IOException("Commande IPMI refusée (code 0x${completionCode.toString(16)})")
        }
        return ipmiMessage.copyOfRange(7, ipmiMessage.size - 1)
    }

    // ---- Session establishment payloads ----

    private fun nextTag(): Int = (++messageTag) and 0xFF

    private fun buildOpenSessionRequest(tag: Int): ByteArray {
        val payload = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(tag.toByte())
            put(privilege.level.toByte()) // requested maximum privilege
            put(0x00); put(0x00) // reserved
            putInt(consoleSessionId)
            // Authentication payload
            put(0x00); put(0x00); put(0x00); put(0x08); put(0x01); put(0x00); put(0x00); put(0x00)
            // Integrity payload
            put(0x01); put(0x00); put(0x00); put(0x08); put(0x01); put(0x00); put(0x00); put(0x00)
            // Confidentiality payload
            put(0x02); put(0x00); put(0x00); put(0x08); put(0x01); put(0x00); put(0x00); put(0x00)
        }.array()
        return wrapPreSessionPayload(0x10, payload)
    }

    private fun buildRakpMessage1(consoleRandom: ByteArray, tag: Int): ByteArray {
        val userBytes = username.toByteArray(Charsets.US_ASCII)
        val payload = ByteBuffer.allocate(4 + 4 + 16 + 1 + 2 + 1 + userBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(tag.toByte()); put(0x00); put(0x00); put(0x00) // message tag + reserved
            putInt(managedSystemSessionId)
            put(consoleRandom)
            put(privilege.rakpByte) // name-only lookup + requested level
            put(0x00); put(0x00) // reserved
            put(userBytes.size.toByte())
            put(userBytes)
        }.array()
        return wrapPreSessionPayload(0x12, payload)
    }

    private fun buildRakpMessage3(authCode: ByteArray, tag: Int): ByteArray {
        val payload = ByteBuffer.allocate(4 + 4 + authCode.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(tag.toByte())
            put(0x00) // status = success
            put(0x00); put(0x00) // reserved
            putInt(managedSystemSessionId)
            put(authCode)
        }.array()
        return wrapPreSessionPayload(0x14, payload)
    }

    private fun wrapPreSessionPayload(payloadType: Int, payload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(1 + 1 + 4 + 4 + 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            // The session header must start with the Auth Type/Format byte identifying this as an
            // RMCP+ (IPMI 2.0) packet; without it the BMC silently drops the datagram.
            put(RMCP_PLUS_AUTH_TYPE)
            put(payloadType.toByte())
            putInt(0) // session id = 0: no session established yet
            putInt(0) // sequence number, ignored pre-session but still present
            putShort(payload.size.toShort())
        }.array()
        return RMCP_HEADER + header + payload
    }

    // ---- Transport ----

    private fun exchangePreSession(request: ByteArray, expectedTag: Int): ByteArray =
        sendAndReceive(request, expectedTag)

    /**
     * Sends a datagram and waits for the reply, retransmitting if none arrives.
     *
     * IPMI runs over UDP with no delivery guarantee, so a lost request simply produces silence.
     * It also matters through a WireGuard tunnel: WireGuard negotiates its handshake only when it
     * first has something to send, and the packet that triggers it is dropped — without a retry
     * the very first IPMI exchange after connecting always failed.
     */
    private fun sendAndReceive(request: ByteArray, expectedTag: Int? = null): ByteArray {
        var lastError: Exception? = null
        repeat(RETRY_ATTEMPTS) {
            try {
                send(request)
                // Discard replies left over from an earlier retransmission rather than treating
                // the first datagram to arrive as the answer.
                while (true) {
                    val response = receive()
                    if (expectedTag == null || tagOf(response) == expectedTag) return response
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Aucune réponse IPMI")
    }

    /** Message tag of a session-setup reply: first byte of the payload. */
    private fun tagOf(packet: ByteArray): Int? =
        if (packet.size > RMCP_PLUS_HEADER_SIZE) packet[RMCP_PLUS_HEADER_SIZE].toInt() and 0xFF else null

    private fun send(data: ByteArray) {
        val sock = socket ?: throw IOException("Session IPMI non ouverte")
        sock.send(DatagramPacket(data, data.size, address, port))
    }

    private fun receive(): ByteArray {
        val sock = socket ?: throw IOException("Session IPMI non ouverte")
        val buffer = ByteArray(1024)
        val packet = DatagramPacket(buffer, buffer.size)
        sock.receive(packet)
        return packet.data.copyOf(packet.length)
    }

    // ---- Helpers ----

    /**
     * IPMI defines its own confidentiality padding rather than using a standard scheme: the
     * plaintext is followed by bytes 01h, 02h, 03h... as needed, then a final byte giving how many
     * pad bytes were added, so that the whole block is a multiple of the 16-byte AES block size.
     * Using PKCS#5 here instead produced packets the BMC silently dropped.
     */
    private fun applyConfidentialityPad(message: ByteArray): ByteArray {
        val padLength = (16 - ((message.size + 1) % 16)) % 16
        val pad = ByteArray(padLength) { (it + 1).toByte() }
        return message + pad + byteArrayOf(padLength.toByte())
    }

    private fun stripConfidentialityPad(decrypted: ByteArray): ByteArray {
        if (decrypted.isEmpty()) return decrypted
        val padLength = decrypted[decrypted.size - 1].toInt() and 0xFF
        val end = decrypted.size - 1 - padLength
        return if (end in 0..decrypted.size) decrypted.copyOfRange(0, end) else decrypted
    }

    /**
     * The RAKP key (K[UID]) is the password zero-padded to the full 20-byte field, not the bare
     * password bytes — confirmed against ipmitool's own key dump for this BMC.
     */
    private fun kuid(): ByteArray {
        val raw = password.toByteArray(Charsets.US_ASCII)
        return raw.copyOf(20)
    }

    /**
     * Strips the RMCP and RMCP+ session headers, returning just the payload: RMCP(4) +
     * auth type(1) + payload type(1) + session id(4) + sequence(4) + payload length(2).
     */
    private fun payloadOf(packet: ByteArray): ByteArray =
        packet.copyOfRange(RMCP_PLUS_HEADER_SIZE, packet.size)

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    private fun twosComplementChecksum(data: ByteArray): Byte {
        var sum = 0
        for (b in data) sum += (b.toInt() and 0xFF)
        return ((-sum) and 0xFF).toByte()
    }

    private fun intLe(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun readIntLe(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readShortLe(data: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (part in parts) {
            part.copyInto(out, pos)
            pos += part.size
        }
        return out
    }
}
