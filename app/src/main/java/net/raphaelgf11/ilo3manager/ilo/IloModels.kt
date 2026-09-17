package net.raphaelgf11.ilo3manager.ilo

enum class PowerState { ON, OFF, UNKNOWN }

enum class HealthLevel { OK, DEGRADED, CRITICAL, UNKNOWN }

data class HardwareComponent(
    val path: String,
    val category: String,
    val label: String,
    val health: HealthLevel,
    val properties: Map<String, String>,
) {
    /** Whether this component reports a HealthState at all (sensors/fans/PSUs do, RAM/CPU don't on this iLO gen). */
    val hasHealthState: Boolean
        get() = properties.keys.any { it.equals("HealthState", ignoreCase = true) }
}

data class HealthSummary(
    val overall: HealthLevel,
    val components: List<HardwareComponent>,
)
