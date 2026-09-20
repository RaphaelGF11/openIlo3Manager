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
        fun from(status: ChassisStatus): HostIndicator = when {
            status.hasCriticalFault -> CRITICAL
            status.hasFault -> FAULT
            status.identifyOn -> UID
            status.power == ChassisPowerState.OFF -> POWERED_OFF
            status.power == ChassisPowerState.ON -> POWERED_ON
            else -> UNKNOWN
        }

        /** Derived from the SSH path, which reports health but knows nothing about the locator LED. */
        fun from(power: PowerState, health: HealthLevel): HostIndicator = when {
            health == HealthLevel.CRITICAL -> CRITICAL
            health == HealthLevel.DEGRADED -> FAULT
            power == PowerState.OFF -> POWERED_OFF
            power == PowerState.ON -> POWERED_ON
            else -> UNKNOWN
        }
    }
}
