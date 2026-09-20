package net.raphaelgf11.ilo3manager.ipmi

/** One sensor as described by the SDR, with its latest reading. */
data class IpmiSensor(
    val number: Int,
    val name: String,
    val sensorType: Int,
    val reading: String,
    val health: SensorHealth,
    /**
     * Byte 3 of Get Sensor Reading, carried raw because it means two different things: asserted
     * event states on a discrete sensor, threshold comparison results on a threshold one.
     */
    val states: Int = 0,
    /** The SDR's Event/Reading Type Code, kept so callers can tell the two families apart. */
    val eventReadingType: Int = 0x01,
) {
    /**
     * True when a threshold sensor reads above one of its upper thresholds.
     *
     * Bits 3, 4 and 5 are the upper non-critical, critical and non-recoverable comparisons. The
     * lower ones say the opposite thing, which matters wherever only one direction is a fault.
     */
    val aboveUpperThreshold: Boolean
        get() = eventReadingType == 0x01 && (states and 0x38) != 0
}

enum class SensorHealth { OK, DEGRADED, CRITICAL, UNAVAILABLE }

/**
 * The result of enumerating the repository.
 *
 * [complete] is the part that matters: a truncated enumeration is not a smaller repository, it is a
 * wrong one — the records that did arrive keep their sensor numbers, so readings taken against a
 * partial list are attributed to whatever component happens to sit at that number.
 */
class SdrRepository(val entries: List<SdrEntry>, val complete: Boolean)

/** Sensor Data Record description, before any reading is taken. */
data class SdrEntry(
    val number: Int,
    val name: String,
    val sensorType: Int,
    val analog: Boolean,
    val unitCode: Int,
    val m: Int,
    val b: Int,
    val exponentB: Int,
    val exponentR: Int,
    val signed: Boolean,
    /**
     * Event/Reading Type Code. 0x01 marks a threshold sensor; anything else is discrete, and its
     * reading byte carries asserted state bits rather than threshold comparisons.
     */
    val eventReadingType: Int = 0x01,
) {
    val thresholdBased: Boolean get() = eventReadingType == 0x01
}

/** Units, limited to those a server BMC actually reports. */
fun unitLabel(code: Int): String = when (code) {
    1 -> " °C"
    2 -> " °F"
    4 -> " V"
    5 -> " A"
    6 -> " W"
    7 -> " J"
    18 -> " RPM"
    else -> ""
}

/**
 * Reads the sensor repository and the sensors themselves.
 *
 * The repository describes what exists and how to interpret raw values; it only changes when the
 * hardware does, so it is worth caching. Readings are then one short command per sensor, which is
 * what makes this far quicker than walking the CLI tree over SSH.
 */
class IpmiSensorReader(private val client: IpmiLanClient) {

    /**
     * Enumerates the SDR. Expensive relative to a reading, so callers should cache the result —
     * but only when [SdrRepository.complete] says the enumeration actually finished.
     */
    fun readRepository(onProgress: (Int, Int) -> Unit = { _, _ -> }): SdrRepository {
        // A reservation is invalidated as soon as anyone else reserves the repository, and this app
        // can have the dashboard, the host list and a widget talking to the same BMC. One attempt
        // therefore is not enough; a lost reservation has to be taken again from the start.
        repeat(REPOSITORY_ATTEMPTS) { attempt ->
            val repository = enumerate(onProgress)
            if (repository.complete) return repository
            if (attempt == REPOSITORY_ATTEMPTS - 1) return repository
        }
        return SdrRepository(emptyList(), complete = false)
    }

    private fun enumerate(onProgress: (Int, Int) -> Unit): SdrRepository {
        val info = client.rawCommand(NET_FN_STORAGE, CMD_GET_SDR_INFO, ByteArray(0))
        val total = if (info.size >= 3) ((info[2].toInt() and 0xFF) shl 8) or (info[1].toInt() and 0xFF) else 0
        val reservation = client.rawCommand(NET_FN_STORAGE, CMD_RESERVE_SDR, ByteArray(0))

        val entries = mutableListOf<SdrEntry>()
        var recordId = 0
        var scanned = 0
        while (recordId != END_OF_SDR) {
            // Stopping here leaves a repository that looks valid but is missing records, and the
            // sensor numbers that follow would be attributed to the wrong components.
            val record = readRecord(reservation, recordId) ?: return SdrRepository(entries, complete = false)
            parseRecord(record.bytes)?.let(entries::add)
            recordId = record.nextRecordId
            onProgress(++scanned, total)
        }
        return SdrRepository(entries, complete = true)
    }

    private class RawRecord(val nextRecordId: Int, val bytes: ByteArray)

    /**
     * Reads one record. The header gives the body length, and the whole record rarely fits in a
     * single response, so it is fetched in chunks small enough never to be truncated.
     */
    private fun readRecord(reservation: ByteArray, recordId: Int): RawRecord? {
        val header = getSdrChunk(reservation, recordId, offset = 0, length = 5) ?: return null
        if (header.size < 7) return null
        val nextRecordId = ((header[1].toInt() and 0xFF) shl 8) or (header[0].toInt() and 0xFF)
        val bodyLength = header[6].toInt() and 0xFF
        val total = 5 + bodyLength

        val bytes = ByteArray(total)
        var copied = 0
        while (copied < total) {
            val want = minOf(CHUNK_SIZE, total - copied)
            val chunk = getSdrChunk(reservation, recordId, copied, want) ?: break
            val payload = chunk.copyOfRange(2, chunk.size)
            val take = minOf(payload.size, total - copied)
            payload.copyInto(bytes, copied, 0, take)
            copied += take
            if (take == 0) break
        }
        return RawRecord(nextRecordId, bytes)
    }

    private fun getSdrChunk(reservation: ByteArray, recordId: Int, offset: Int, length: Int): ByteArray? =
        runCatching {
            client.rawCommand(
                NET_FN_STORAGE,
                CMD_GET_SDR,
                byteArrayOf(
                    reservation.getOrElse(0) { 0 },
                    reservation.getOrElse(1) { 0 },
                    (recordId and 0xFF).toByte(),
                    ((recordId shr 8) and 0xFF).toByte(),
                    offset.toByte(),
                    length.toByte(),
                ),
            )
        }.getOrNull()

    /** Only Full (0x01) and Compact (0x02) records describe a sensor; the rest are inventory data. */
    private fun parseRecord(record: ByteArray): SdrEntry? {
        if (record.size < 8) return null
        return when (record[3].toInt() and 0xFF) {
            0x01 -> parseFull(record)
            0x02 -> parseCompact(record)
            else -> null
        }
    }

    private fun parseFull(r: ByteArray): SdrEntry? {
        if (r.size < 48) return null
        val units1 = r[20].toInt() and 0xFF
        val m = tenBitSigned(r[24].toInt() and 0xFF, (r[25].toInt() and 0xC0) shr 6)
        val b = tenBitSigned(r[26].toInt() and 0xFF, (r[27].toInt() and 0xC0) shr 6)
        val exponents = r[29].toInt() and 0xFF
        return SdrEntry(
            number = r[7].toInt() and 0xFF,
            name = idString(r, 47),
            sensorType = r[12].toInt() and 0xFF,
            analog = (units1 shr 6) and 0x03 != 3,
            unitCode = r[21].toInt() and 0xFF,
            m = m,
            b = b,
            exponentB = fourBitSigned(exponents and 0x0F),
            exponentR = fourBitSigned((exponents shr 4) and 0x0F),
            signed = (units1 shr 6) and 0x03 == 2,
            eventReadingType = r[13].toInt() and 0xFF,
        )
    }

    private fun parseCompact(r: ByteArray): SdrEntry? {
        if (r.size < 32) return null
        return SdrEntry(
            number = r[7].toInt() and 0xFF,
            name = idString(r, 31),
            sensorType = r[12].toInt() and 0xFF,
            // Compact records carry discrete states only, never a scaled value.
            analog = false,
            unitCode = 0,
            m = 1, b = 0, exponentB = 0, exponentR = 0, signed = false,
            eventReadingType = r[13].toInt() and 0xFF,
        )
    }

    /** The ID string is length-prefixed; only the 8-bit ASCII encoding appears on these devices. */
    private fun idString(r: ByteArray, typeLengthIndex: Int): String {
        if (typeLengthIndex >= r.size) return ""
        val length = (r[typeLengthIndex].toInt() and 0x1F)
        val start = typeLengthIndex + 1
        val end = minOf(start + length, r.size)
        if (start >= end) return ""
        return String(r, start, end - start, Charsets.US_ASCII).trim()
    }

    /** Reads one sensor; returns null when the BMC reports it as unavailable or not present. */
    fun readSensor(entry: SdrEntry): IpmiSensor? {
        val resp = runCatching {
            client.rawCommand(NET_FN_SENSOR, CMD_GET_SENSOR_READING, byteArrayOf(entry.number.toByte()))
        }.getOrNull() ?: return null
        if (resp.size < 2) return null

        val flags = resp[1].toInt() and 0xFF
        // Bit 5 set means the reading is unavailable; bit 6 clear means scanning is disabled.
        val unavailable = (flags and 0x20) != 0 || (flags and 0x40) == 0
        val comparison = if (resp.size > 2) resp[2].toInt() and 0xFF else 0

        val health = when {
            unavailable -> SensorHealth.UNAVAILABLE
            entry.thresholdBased -> thresholdHealth(comparison)
            // Sensor-specific discrete power supplies are worth decoding: they are the one family
            // where a plain reading distinguishes a working unit from a failed one.
            entry.eventReadingType == 0x6F && entry.sensorType == SENSOR_TYPE_POWER_SUPPLY ->
                powerSupplyHealth(comparison)
            // Generic redundancy sensors (IPMI table 42-2) are well enough defined to decode, and
            // they carry exactly the "still running, but no longer protected" signal.
            entry.eventReadingType == EVENT_TYPE_REDUNDANCY -> redundancyHealth(comparison)
            // Any other discrete sensor reports asserted state bits whose meaning depends on the
            // sensor type, and several of them assert bit 0 simply to say "present". Reading those
            // as threshold comparisons is what marked healthy fans and power supplies as failing.
            else -> SensorHealth.OK
        }

        val raw = resp[0].toInt() and 0xFF
        val reading = when {
            unavailable -> "indisponible"
            entry.analog -> formatAnalog(entry, raw)
            else -> "0x%02x".format(raw)
        }
        return IpmiSensor(
            number = entry.number,
            name = entry.name,
            sensorType = entry.sensorType,
            reading = reading,
            health = health,
            states = comparison,
            eventReadingType = entry.eventReadingType,
        )
    }

    private fun thresholdHealth(comparison: Int): SensorHealth = when {
        comparison and 0x24 != 0 -> SensorHealth.CRITICAL // upper/lower critical
        comparison and 0x12 != 0 -> SensorHealth.CRITICAL // upper/lower non-recoverable
        comparison and 0x09 != 0 -> SensorHealth.DEGRADED // upper/lower non-critical
        else -> SensorHealth.OK
    }

    /**
     * Power supply state bits (IPMI table 42-3). Bit 0 is presence and says nothing about health,
     * which is exactly the trap: a seated, working supply asserts it.
     *
     * A failed supply is degraded rather than critical. These machines are built with redundant
     * supplies: one failing costs the redundancy, not the server, and the chassis says so itself by
     * lighting its health LED amber rather than red.
     */
    private fun powerSupplyHealth(states: Int): SensorHealth = when {
        states and 0x02 != 0 -> SensorHealth.DEGRADED // failure detected
        states and 0x08 != 0 -> SensorHealth.DEGRADED // input lost
        states and 0x04 != 0 -> SensorHealth.DEGRADED // predictive failure
        states and 0x40 != 0 -> SensorHealth.DEGRADED // configuration error
        else -> SensorHealth.OK
    }

    /** Redundancy state bits (IPMI table 42-2): bit 0 is full redundancy, the rest are losses. */
    private fun redundancyHealth(states: Int): SensorHealth = when {
        states and 0x01 != 0 -> SensorHealth.OK // fully redundant
        states and 0x06 != 0 -> SensorHealth.DEGRADED // redundancy lost or degraded
        else -> SensorHealth.OK
    }

    /** Applies the SDR's linear conversion: value = (M * raw + B * 10^Bexp) * 10^Rexp. */
    private fun formatAnalog(entry: SdrEntry, raw: Int): String {
        val rawValue = if (entry.signed && raw > 127) raw - 256 else raw
        val value = (entry.m * rawValue + entry.b * pow10(entry.exponentB)) * pow10(entry.exponentR)
        val rounded = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)
        return rounded + unitLabel(entry.unitCode)
    }

    private fun pow10(exponent: Int): Double = Math.pow(10.0, exponent.toDouble())

    private fun tenBitSigned(low: Int, high: Int): Int {
        val value = (high shl 8) or low
        return if (value and 0x200 != 0) value - 0x400 else value
    }

    private fun fourBitSigned(value: Int): Int = if (value and 0x08 != 0) value - 0x10 else value

    private companion object {
        const val NET_FN_STORAGE = 0x0A
        const val NET_FN_SENSOR = 0x04
        const val CMD_GET_SDR_INFO = 0x20
        const val CMD_RESERVE_SDR = 0x22
        const val CMD_GET_SDR = 0x23
        const val CMD_GET_SENSOR_READING = 0x2D
        const val END_OF_SDR = 0xFFFF
        const val SENSOR_TYPE_POWER_SUPPLY = 0x08
        const val REPOSITORY_ATTEMPTS = 3
        const val EVENT_TYPE_REDUNDANCY = 0x0B
        /** Small enough that a record chunk plus its two-byte prefix always fits in one reply. */
        const val CHUNK_SIZE = 16
    }
}
