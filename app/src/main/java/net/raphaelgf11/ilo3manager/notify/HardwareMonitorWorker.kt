package net.raphaelgf11.ilo3manager.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.NotificationSettingsRepository
import net.raphaelgf11.ilo3manager.ilo.HealthLevel
import net.raphaelgf11.ilo3manager.ilo.PowerState

/**
 * Periodic check-in for every host that has notifications enabled: opens a fresh SSH connection
 * per host (like a mail app's periodic sync), compares the result against the last known state
 * to detect *transitions*, and notifies only for the event types the user enabled for that host.
 *
 * Each host is only actually re-checked once its own configured interval has elapsed; the
 * worker itself runs at the shortest interval configured across all monitored hosts (WorkManager
 * enforces a 15-minute floor for periodic work).
 */
class HardwareMonitorWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        NotificationHelper.ensureChannel(applicationContext)
        val hostRepository = HostRepository(applicationContext)
        val notificationSettings = NotificationSettingsRepository(applicationContext)
        val stateStore = MonitorStateStore(applicationContext)

        val hostsById = hostRepository.getHosts().associateBy { it.id }
        val now = System.currentTimeMillis()

        for ((hostId, settings) in notificationSettings.enabledHosts()) {
            val host = hostsById[hostId] ?: continue
            val previous = stateStore.get(hostId)
            val intervalMs = settings.intervalMinutes.coerceAtLeast(1) * 60_000L
            if (now - previous.lastCheckedAtMillis < intervalMs) continue

            when (val result = HardwareMonitorCheck.check(host)) {
                is HardwareMonitorCheck.Result.Failure -> {
                    if (settings.notifyOnConnectionFailure && previous.connectionOk != false) {
                        NotificationHelper.notify(
                            applicationContext,
                            notificationId(hostId, 0),
                            host.name,
                            "Échec de connexion SSH : ${result.message}",
                        )
                    }
                    stateStore.set(hostId, previous.copy(lastCheckedAtMillis = now, connectionOk = false))
                }
                is HardwareMonitorCheck.Result.Success -> {
                    val previousHealth = runCatching { HealthLevel.valueOf(previous.health) }.getOrDefault(HealthLevel.UNKNOWN)
                    val previousPower = runCatching { PowerState.valueOf(previous.powerState) }.getOrDefault(PowerState.UNKNOWN)

                    if (settings.notifyOnCritical && result.health == HealthLevel.CRITICAL && previousHealth != HealthLevel.CRITICAL) {
                        NotificationHelper.notify(
                            applicationContext,
                            notificationId(hostId, 1),
                            host.name,
                            "État critique" + describeDegraded(result.degradedComponentLabels),
                        )
                    } else if (
                        settings.notifyOnDegraded &&
                        result.health == HealthLevel.DEGRADED &&
                        previousHealth != HealthLevel.DEGRADED &&
                        previousHealth != HealthLevel.CRITICAL
                    ) {
                        NotificationHelper.notify(
                            applicationContext,
                            notificationId(hostId, 2),
                            host.name,
                            "Dégradation matérielle" + describeDegraded(result.degradedComponentLabels),
                        )
                    }

                    if (settings.notifyOnPoweredOff && result.power == PowerState.OFF && previousPower == PowerState.ON) {
                        NotificationHelper.notify(applicationContext, notificationId(hostId, 3), host.name, "Le serveur s'est éteint")
                    } else if (settings.notifyOnPoweredOn && result.power == PowerState.ON && previousPower == PowerState.OFF) {
                        NotificationHelper.notify(applicationContext, notificationId(hostId, 4), host.name, "Le serveur s'est allumé")
                    }

                    stateStore.set(
                        hostId,
                        previous.copy(
                            lastCheckedAtMillis = now,
                            connectionOk = true,
                            powerState = result.power.name,
                            health = result.health.name,
                        ),
                    )
                }
            }
        }
        return Result.success()
    }

    private fun describeDegraded(labels: List<String>): String =
        if (labels.isEmpty()) "" else " : ${labels.joinToString(", ")}"

    private fun notificationId(hostId: String, eventType: Int): Int = hostId.hashCode() * 10 + eventType
}
