package net.raphaelgf11.ilo3manager.widget

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ilo.IpmiSdrCache
import net.raphaelgf11.ilo3manager.ipmi.ChassisPowerState
import net.raphaelgf11.ilo3manager.ipmi.IpmiLanClient
import net.raphaelgf11.ilo3manager.ipmi.IpmiSensor
import net.raphaelgf11.ilo3manager.ipmi.IpmiSensorReader
import net.raphaelgf11.ilo3manager.ipmi.SensorHealth
import net.raphaelgf11.ilo3manager.ipmi.retryingIpmiPoll
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager

/**
 * Reads a host's front panel over IPMI.
 *
 * IPMI only, deliberately: the widget refreshes in the background, where the seconds an SSH login
 * costs would be spent with the screen off and the radio held awake for nothing.
 */
object PanelReader {

    /**
     * Blocking. Takes one reading, and two more shots at it if that one fails.
     *
     * IPMI is UDP, and the widget refreshes unattended: a poll lost to the network would blank the
     * panel until the next tick, which reads as "the server went away". A fresh session costs a
     * second or two, far less than showing a dark panel for a machine that is running.
     */
    fun read(host: SshHost): PanelState = retryingIpmiPoll { readOnce(host) }

    private fun readOnce(host: SshHost): PanelState {
        val endpoint = HostTunnelManager.endpointFor(host, host.ipmiPort, udp = true)
        val client = IpmiLanClient(
            endpoint.host,
            endpoint.port,
            host.username,
            host.password,
            host.ipmiPrivilege,
        )
        return try {
            client.open()
            val status = client.getChassisStatus()
            val power = when (status.power) {
                ChassisPowerState.ON -> PowerLed.ON
                // The BMC answered, so the machine is plugged in: powered down is the amber button,
                // never a dark panel. Darkness is reserved for getting no answer at all.
                ChassisPowerState.OFF -> PowerLed.OFF
                ChassisPowerState.UNKNOWN -> PowerLed.OFF
            }

            // Sensors are only worth reading when the machine is running; on standby the SDR is
            // readable but every reading is "no reading", and the panel is dark anyway.
            val sensors = if (power == PowerLed.ON) readSensors(client, host.id) else emptyList()
            val panel = fromSensors(power, status.identifyOn, status.hasCriticalFault, status.hasFault, sensors)
            // Only worth asking when the machine is running: a powered-down server answers nothing,
            // and four timeouts would be four seconds added to every refresh.
            if (power == PowerLed.ON) panel.copy(nics = pingNics(host)) else panel
        } finally {
            client.close()
        }
    }

    /**
     * Lights each network indicator whose configured address answers an echo.
     *
     * All four at once: done in turn, four unanswered addresses would add four timeouts to every
     * refresh. An address left blank stays dark rather than being reported as down — nothing was
     * claimed about that port, so nothing is shown.
     */
    private fun pingNics(host: SshHost): List<LinkLed> = runBlocking {
        host.nicAddresses.map { address ->
            async(Dispatchers.IO) {
                if (address.isBlank()) {
                    LinkLed.OFF
                } else if (HostTunnelManager.ping(host, address)) {
                    LinkLed.GREEN
                } else {
                    LinkLed.OFF
                }
            }
        }.awaitAll()
    }

    /**
     * Every sensor the BMC reports, for the diagnostic list in the widget's settings.
     *
     * The mapping from sensor names to panel positions can only be as good as the names, which come
     * from the firmware; being able to see them is what makes a wrong indicator fixable.
     */
    fun readAllSensors(host: SshHost): List<IpmiSensor> {
        val endpoint = HostTunnelManager.endpointFor(host, host.ipmiPort, udp = true)
        val client = IpmiLanClient(
            endpoint.host,
            endpoint.port,
            host.username,
            host.password,
            host.ipmiPrivilege,
        )
        return try {
            client.open()
            readSensors(client, host.id)
        } finally {
            client.close()
        }
    }

    /**
     * Shares the application's repository cache rather than enumerating its own.
     *
     * The widget, the host list and the dashboard all query the same BMC; each enumerating
     * separately means their reservations cancel one another, and every one of them comes away
     * with a different truncated repository.
     */
    private fun readSensors(client: IpmiLanClient, hostId: String): List<IpmiSensor> =
        IpmiSdrCache.sensors(client, hostId)

    /**
     * Maps sensor readings onto panel positions.
     *
     * The names come from the BMC's own SDR and vary between firmware revisions, so matching is by
     * keyword and slot number rather than by exact string. Anything unrecognised is left dark,
     * which is also what the real panel does: an unlit indicator means "nothing to report here".
     */
    internal fun fromSensors(
        power: PowerLed,
        uid: Boolean,
        criticalFault: Boolean,
        anyFault: Boolean,
        sensors: List<IpmiSensor>,
    ): PanelState {
        if (power != PowerLed.ON) {
            return PanelState(power = power, uid = uid)
        }

        val worst = sensors.maxOfOrNull { it.health.rank() } ?: SensorHealth.OK.rank()
        val health = when {
            criticalFault || worst == SensorHealth.CRITICAL.rank() -> HealthLed.RED
            anyFault || worst == SensorHealth.DEGRADED.rank() -> HealthLed.AMBER
            else -> HealthLed.GREEN
        }

        return PanelState(
            power = power,
            health = health,
            uid = uid,
            // Filled in afterwards by pinging the addresses the user assigned: no sensor on this
            // hardware reports link, so nothing here can.
            nics = List(4) { LinkLed.OFF },
            psus = List(2) { index -> ledFor(sensors, listOf("power supply", "ps "), index + 1) },
            overTemp = overTempLed(sensors),
            powerCap = Led.OFF,
            dimmsLeft = List(9) { index -> ledForDimm(sensors, bank = 0, slot = index + 1) },
            dimmsRight = List(9) { index -> ledForDimm(sensors, bank = 1, slot = index + 1) },
            procs = List(2) { index -> ledFor(sensors, listOf("proc", "cpu"), index + 1) },
            ampStatus = Led.OFF,
            fans = List(6) { index -> ledFor(sensors, listOf("fan"), index + 1) },
        )
    }

    /** Worst health among sensors whose name carries one of [keywords] and the given [slot]. */
    private fun ledFor(sensors: List<IpmiSensor>, keywords: List<String>, slot: Int): Led {
        val matching = sensors.filter { sensor ->
            val name = sensor.name.lowercase()
            keywords.any { name.contains(it) } && mentionsSlot(name, slot)
        }
        return worstLed(matching)
    }

    /**
     * The indicator is called OVER TEMP, so only an upper-threshold excursion lights it.
     *
     * Taking the worst health across every temperature sensor would also catch a reading below a
     * lower threshold — a cold spare or an unpopulated socket — and report it as overheating.
     */
    private fun overTempLed(sensors: List<IpmiSensor>): Led {
        val tooHot = sensors.any { sensor ->
            val name = sensor.name.lowercase()
            (name.contains("temp") || name.contains("ambient") || name.contains("inlet")) &&
                sensor.aboveUpperThreshold
        }
        return if (tooHot) Led.AMBER else Led.OFF
    }

    /**
     * DIMM sensors are named after their board position rather than a panel coordinate, so the two
     * banks are told apart by the processor they hang off when the name says so, and otherwise by
     * splitting the slot numbering in half.
     */
    private fun ledForDimm(sensors: List<IpmiSensor>, bank: Int, slot: Int): Led {
        val matching = sensors.filter { sensor ->
            val name = sensor.name.lowercase()
            if (!name.contains("dimm") && !name.contains("mem")) return@filter false
            val sensorBank = when {
                name.contains("cpu2") || name.contains("proc 2") || name.contains("p2") -> 1
                name.contains("cpu1") || name.contains("proc 1") || name.contains("p1") -> 0
                else -> null
            }
            if (sensorBank != null && sensorBank != bank) return@filter false
            mentionsSlot(name, slot)
        }
        return worstLed(matching)
    }

    /** Whether [name] refers to slot [slot] without matching 1 inside 10, 11 and so on. */
    private fun mentionsSlot(name: String, slot: Int): Boolean =
        Regex("(?<!\\d)$slot(?!\\d)").containsMatchIn(name)

    /**
     * A component indicator is amber or nothing — the hardware has no red per-component LED, and
     * severity is carried by the overall health LED instead.
     */
    private fun worstLed(sensors: List<IpmiSensor>): Led = when {
        sensors.any { it.health == SensorHealth.CRITICAL || it.health == SensorHealth.DEGRADED } -> Led.AMBER
        // Healthy and unknown alike leave the indicator dark, as the hardware does.
        else -> Led.OFF
    }

    private fun SensorHealth.rank(): Int = when (this) {
        SensorHealth.CRITICAL -> 3
        SensorHealth.DEGRADED -> 2
        SensorHealth.OK -> 1
        SensorHealth.UNAVAILABLE -> 0
    }
}
