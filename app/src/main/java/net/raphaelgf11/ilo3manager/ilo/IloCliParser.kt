package net.raphaelgf11.ilo3manager.ilo

/**
 * Parses HP/HPE iLO3 SMASH-CLP style CLI output, e.g. for `show /system1/fan1`:
 *
 * ```
 * /system1/fan1
 *   Targets
 *   Properties
 *     DeviceID=Fan 1
 *     HealthState=Ok
 *   Verbs
 *     cd version exit show
 * ```
 *
 * The exact wording is only confirmed against one DL380 G7 / iLO3 unit, so
 * parsing is deliberately tolerant: unknown fields are kept as raw
 * properties instead of causing a failure.
 */
object IloCliParser {

    fun parsePowerState(raw: String): PowerState {
        val match = Regex("currently:\\s*(on|off)", RegexOption.IGNORE_CASE).find(raw)
            ?: Regex("power\\s*[:=]\\s*(on|off)", RegexOption.IGNORE_CASE).find(raw)
        return when (match?.groupValues?.get(1)?.lowercase()) {
            "on" -> PowerState.ON
            "off" -> PowerState.OFF
            else -> PowerState.UNKNOWN
        }
    }

    /** Extracts the child target names listed under the "Targets" section of a `show` response. */
    fun parseTargets(raw: String): List<String> {
        val lines = raw.lines()
        val targetsIndex = lines.indexOfFirst { it.trim() == "Targets" }
        if (targetsIndex < 0) return emptyList()
        val result = mutableListOf<String>()
        for (i in (targetsIndex + 1) until lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed == "Properties" || trimmed == "Verbs" || trimmed == "Associations") break
            // Target names are simple identifiers (e.g. "fan1"), not "key=value" pairs.
            if (!trimmed.contains("=")) result.add(trimmed)
        }
        return result
    }

    /** Extracts the "key=value" pairs listed under the "Properties" section of a `show` response. */
    fun parseProperties(raw: String): Map<String, String> {
        val lines = raw.lines()
        val propsIndex = lines.indexOfFirst { it.trim() == "Properties" }
        if (propsIndex < 0) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (i in (propsIndex + 1) until lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.isEmpty()) continue
            if (trimmed == "Verbs" || trimmed == "Associations") break
            val separatorIndex = trimmed.indexOf('=')
            if (separatorIndex <= 0) continue
            val key = trimmed.substring(0, separatorIndex).trim()
            val value = trimmed.substring(separatorIndex + 1).trim()
            result[key] = value
        }
        return result
    }

    fun healthFromProperties(properties: Map<String, String>): HealthLevel {
        val raw = properties.entries.firstOrNull { it.key.equals("HealthState", ignoreCase = true) }
            ?.value ?: return HealthLevel.UNKNOWN
        return healthFromValue(raw)
    }

    /**
     * Only an explicit "Critical" means the whole system is at risk. A component reporting a
     * failure/warning/degraded state while the server keeps running (e.g. a dead, ignored DIMM,
     * or a single failed drive in a redundant array) is a normal, non-critical error.
     */
    fun healthFromValue(rawValue: String): HealthLevel {
        val raw = rawValue.lowercase()
        return when {
            raw.contains("critical") -> HealthLevel.CRITICAL
            raw.contains("degrad") || raw.contains("warn") || raw.contains("caution") ||
                raw.contains("fail") || raw.contains("error") -> HealthLevel.DEGRADED
            raw.contains("ok") -> HealthLevel.OK
            else -> HealthLevel.UNKNOWN
        }
    }

    data class DriveBay(
        val bay: Int,
        val group: Int,
        val firmwareVersion: String,
        val status: String,
        val uid: String,
    )

    /**
     * Parses the `show /system1/drivesN` format, which is not simple "key=value" properties but
     * grouped free-text lines, e.g.:
     * ```
     * Group=1, Firmware Version=1.14
     * Bay 1 - drive status=Ok; UID=Off
     * Bay 2 - drive status=Ok; UID=Off
     * Group=2, Firmware Version=1.14
     * Bay 5 - drive status=Ok; UID=Off
     * ```
     */
    fun parseDriveBays(raw: String): List<DriveBay> {
        val groupRegex = Regex("""Group=(\d+),\s*Firmware Version=([\w.]+)""")
        val bayRegex = Regex("""Bay\s*(\d+)\s*-\s*drive status=(\w+);\s*UID=(\w+)""", RegexOption.IGNORE_CASE)
        val result = mutableListOf<DriveBay>()
        var currentGroup = 0
        var currentFirmware = ""
        for (line in raw.lines()) {
            val trimmed = line.trim()
            groupRegex.find(trimmed)?.let {
                currentGroup = it.groupValues[1].toIntOrNull() ?: currentGroup
                currentFirmware = it.groupValues[2]
            }
            bayRegex.find(trimmed)?.let {
                result += DriveBay(
                    bay = it.groupValues[1].toIntOrNull() ?: 0,
                    group = currentGroup,
                    firmwareVersion = currentFirmware,
                    status = it.groupValues[2],
                    uid = it.groupValues[3],
                )
            }
        }
        return result
    }

    fun categoryOf(targetName: String): String {
        return targetName.trimEnd { it.isDigit() }
    }

    fun labelOf(targetName: String, properties: Map<String, String>): String {
        return properties["ElementName"]
            ?: properties["DeviceID"]
            ?: properties["name"]
            ?: properties["location"]
            ?: targetName
    }

    fun overallHealth(levels: Collection<HealthLevel>): HealthLevel {
        return when {
            levels.any { it == HealthLevel.CRITICAL } -> HealthLevel.CRITICAL
            levels.any { it == HealthLevel.DEGRADED } -> HealthLevel.DEGRADED
            levels.any { it == HealthLevel.OK } -> HealthLevel.OK
            else -> HealthLevel.UNKNOWN
        }
    }
}
