package net.raphaelgf11.ilo3manager.data

data class HostNotificationSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 15,
    val notifyOnConnectionFailure: Boolean = true,
    val notifyOnDegraded: Boolean = true,
    val notifyOnCritical: Boolean = true,
    val notifyOnPoweredOff: Boolean = true,
    val notifyOnPoweredOn: Boolean = false,
)
