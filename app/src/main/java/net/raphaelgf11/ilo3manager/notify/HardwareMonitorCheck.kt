package net.raphaelgf11.ilo3manager.notify

import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ilo.HealthLevel
import net.raphaelgf11.ilo3manager.ilo.IloCliParser
import net.raphaelgf11.ilo3manager.ilo.PowerState
import net.raphaelgf11.ilo3manager.ssh.IloCliClient

/**
 * A single, self-contained SSH health check used by [HardwareMonitorWorker]: opens its own fresh
 * connection (as requested, mirroring how a Gmail-style periodic sync reconnects each time
 * instead of relying on the app's in-memory session) and closes it before returning.
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

    suspend fun check(host: SshHost): Result {
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
