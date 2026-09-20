package net.raphaelgf11.ilo3manager.ilo

import net.raphaelgf11.ilo3manager.ipmi.ChassisPowerState
import net.raphaelgf11.ilo3manager.ipmi.ChassisStatus

/**
 * What the status dot next to a host shows.
 *
 * Ordered by precedence when several apply at once: a critical fault outranks anything else, and
 * the locator LED outranks the plain power state because it is switched on deliberately to find a
 * machine — showing "powered on" instead would hide the very thing the user asked for.
 */
enum class HostIndicator {
    /** Not connected, or no state has been read yet. */
    UNKNOWN,

    /** A connection or a state read is under way; nothing conclusive to show yet. */
    CONNECTING,
    CRITICAL,
    FAULT,
    /** The chassis locator LED is lit. */
    UID,
    POWERED_OFF,
    POWERED_ON,
    ;

    companion object {
        /**
         * Single rule for the dot, so every caller ranks the same way.
         *
         * A fault outranks the locator: the blue lamp is on because someone is looking for the
         * machine, but if it is also broken, that is what they need to be told first. The SSH path
         * knows nothing about the locator and passes [identifyOn] as false.
         */
        fun from(
            power: PowerState,
            health: HealthLevel,
            identifyOn: Boolean = false,
        ): HostIndicator = when {
            health == HealthLevel.CRITICAL -> CRITICAL
            health == HealthLevel.DEGRADED -> FAULT
            identifyOn -> UID
            power == PowerState.OFF -> POWERED_OFF
            power == PowerState.ON -> POWERED_ON
            else -> UNKNOWN
        }

        fun powerStateOf(status: ChassisStatus): PowerState = when (status.power) {
            ChassisPowerState.ON -> PowerState.ON
            ChassisPowerState.OFF -> PowerState.OFF
            ChassisPowerState.UNKNOWN -> PowerState.UNKNOWN
        }
    }
}
