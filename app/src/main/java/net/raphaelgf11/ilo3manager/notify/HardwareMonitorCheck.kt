package net.raphaelgf11.ilo3manager.notify

import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ilo.HealthLevel
import net.raphaelgf11.ilo3manager.ilo.IloCliParser
import net.raphaelgf11.ilo3manager.ilo.PowerState
import net.raphaelgf11.ilo3manager.ilo.IpmiSdrCache
import net.raphaelgf11.ilo3manager.ipmi.ChassisPowerState
import net.raphaelgf11.ilo3manager.ipmi.IpmiLanClient
import net.raphaelgf11.ilo3manager.ipmi.SensorHealth
import net.raphaelgf11.ilo3manager.ssh.IloCliClient
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager

/**
 * A single, self-contained health check used by [HardwareMonitorWorker].
 *
 * It opens its own fresh connection each round, mirroring how a mail app's periodic sync reconnects
 * rather than relying on the app's in-memory session, and closes it before returning. That is
 * precisely why the transport matters here: over SSH each round costs seconds and one of the iLO's
 * very few concurrent sessions, per monitored host.
 */
object HardwareMonitorCheck {

    private val QUICK_SCAN_CATEGORIES = setOf("fan", "powersupply")

    sealed class Result {
        data class Success(
            val power: PowerState,
            val health: HealthLevel,
            val degradedComponentLabels: List<String>,
        ) : Result()

        data class Failure(val message: String) : Result()
    }

    suspend fun check(host: SshHost): Result =
        if (usesIpmi(host)) checkOverIpmi(host) else checkOverSsh(host)

    /** IPMI is only usable when enabled, credentialled, and reachable by a tunnel carrying UDP. */
    private fun usesIpmi(host: SshHost): Boolean =
        host.notificationsOverIpmi && host.ipmiEnabled && host.password.isNotBlank() &&
            HostTunnelManager.supportsUdp(host)

    /**
     * One IPMI session: chassis status for power, then the sensors for health.
     *
     * Chassis status alone is not a health signal — its fault bits stay clear for a failed power
     * supply — so the sensors have to be read too. The repository is cached across sessions, so
     * only the first round pays for enumerating it.
     */
    private fun checkOverIpmi(host: SshHost): Result {
        val endpoint = HostTunnelManager.endpointFor(host, host.ipmiPort, udp = true)
        val ipmi = IpmiLanClient(endpoint.host, endpoint.port, host.username, host.password, host.ipmiPrivilege)
        return try {
            ipmi.open()
            val status = ipmi.getChassisStatus()
            val power = when (status.power) {
                ChassisPowerState.ON -> PowerState.ON
                ChassisPowerState.OFF -> PowerState.OFF
                ChassisPowerState.UNKNOWN -> PowerState.UNKNOWN
            }
            val sensors = IpmiSdrCache.sensors(ipmi, host.id)
            val degraded = sensors
                .filter { it.health == SensorHealth.DEGRADED || it.health == SensorHealth.CRITICAL }
                .map { it.name }
            val worst = sensors.fold(HealthLevel.OK) { acc, sensor ->
                val level = when (sensor.health) {
                    SensorHealth.CRITICAL -> HealthLevel.CRITICAL
                    SensorHealth.DEGRADED -> HealthLevel.DEGRADED
                    SensorHealth.OK, SensorHealth.UNAVAILABLE -> HealthLevel.OK
                }
                if (level.ordinal > acc.ordinal) level else acc
            }
            val health = when {
                status.hasCriticalFault || worst == HealthLevel.CRITICAL -> HealthLevel.CRITICAL
                status.hasFault || worst == HealthLevel.DEGRADED -> HealthLevel.DEGRADED
                else -> HealthLevel.OK
            }
            Result.Success(power, health, degraded)
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Connexion IPMI impossible")
        } finally {
            ipmi.close()
        }
    }

    private suspend fun checkOverSsh(host: SshHost): Result {
        val client = IloCliClient()
        return try {
            client.connect(host)
            val power = IloCliParser.parsePowerState(client.runCommand("power"))
            val rootTargets = IloCliParser.parseTargets(client.runCommand("show /system1"))
            val quickTargets = rootTargets.filter { IloCliParser.categoryOf(it) in QUICK_SCAN_CATEGORIES }

            val levels = mutableListOf<HealthLevel>()
            val degraded = mutableListOf<String>()
            for (target in quickTargets) {
                try {
                    val raw = client.runCommand("show /system1/$target")
                    val properties = IloCliParser.parseProperties(raw)
                    val level = IloCliParser.healthFromProperties(properties)
                    levels += level
                    if (level == HealthLevel.DEGRADED || level == HealthLevel.CRITICAL) {
                        degraded += IloCliParser.labelOf(target, properties)
                    }
                } catch (_: Exception) {
                    // A single component failing to answer shouldn't abort the whole check.
                }
            }
            Result.Success(power, IloCliParser.overallHealth(levels), degraded)
        } catch (e: Exception) {
            Result.Failure(e.message ?: "Connexion impossible")
        } finally {
            client.disconnect()
        }
    }
}
