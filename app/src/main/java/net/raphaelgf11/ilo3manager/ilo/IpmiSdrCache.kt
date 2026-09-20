package net.raphaelgf11.ilo3manager.ilo

import net.raphaelgf11.ilo3manager.ipmi.IpmiLanClient
import net.raphaelgf11.ilo3manager.ipmi.IpmiSensor
import net.raphaelgf11.ilo3manager.ipmi.IpmiSensorReader
import net.raphaelgf11.ilo3manager.ipmi.SdrEntry
import net.raphaelgf11.ilo3manager.ipmi.SensorHealth
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps each host's sensor repository between IPMI sessions.
 *
 * Reading the repository costs well over a second, while reading the sensors themselves costs a
 * couple of hundred milliseconds — and the repository only changes when hardware does. Caching it
 * per host, rather than per session or per screen, is what lets the host list poll sensor health at
 * all: without it every poll would re-read the whole repository.
 */
object IpmiSdrCache {

    private val entriesByHost = ConcurrentHashMap<String, List<SdrEntry>>()

    /**
     * Only one enumeration at a time, across the whole app.
     *
     * A repository reservation is invalidated the moment anyone else reserves, and the dashboard,
     * the host list and the home screen widget all talk to the same BMC. Left to run concurrently
     * they cancel each other's reservations, and each comes away with a different truncated list.
     */
    private val enumerating = java.util.concurrent.locks.ReentrantLock()

    fun entries(
        client: IpmiLanClient,
        hostId: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<SdrEntry> {
        entriesByHost[hostId]?.let { return it }
        enumerating.lock()
        try {
            // Another caller may have finished while this one waited for the lock.
            entriesByHost[hostId]?.let { return it }
            val repository = IpmiSensorReader(client).readRepository(onProgress)
            // A partial list is never cached: its records keep their sensor numbers, so readings
            // taken against it land on the wrong components and stay wrong until the app restarts.
            if (repository.complete) entriesByHost[hostId] = repository.entries
            return repository.entries
        } finally {
            enumerating.unlock()
        }
    }

    fun sensors(client: IpmiLanClient, hostId: String): List<IpmiSensor> {
        val reader = IpmiSensorReader(client)
        return entries(client, hostId).mapNotNull { reader.readSensor(it) }
    }

    /** The worst health across every sensor; unreadable sensors do not count against the host. */
    fun worstHealth(client: IpmiLanClient, hostId: String): HealthLevel =
        sensors(client, hostId).fold(HealthLevel.OK) { worst, sensor ->
            val level = when (sensor.health) {
                SensorHealth.CRITICAL -> HealthLevel.CRITICAL
                SensorHealth.DEGRADED -> HealthLevel.DEGRADED
                SensorHealth.OK, SensorHealth.UNAVAILABLE -> HealthLevel.OK
            }
            if (level.ordinal > worst.ordinal) level else worst
        }

    /** Called when the hardware may have changed under us, so the next read rebuilds the list. */
    fun invalidate(hostId: String) {
        entriesByHost.remove(hostId)
    }
}
