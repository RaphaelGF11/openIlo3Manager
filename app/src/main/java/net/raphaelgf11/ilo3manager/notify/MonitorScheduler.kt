package net.raphaelgf11.ilo3manager.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import net.raphaelgf11.ilo3manager.data.NotificationSettingsRepository
import java.util.concurrent.TimeUnit

/**
 * A single periodic worker checks in on every monitored host; it runs at the shortest interval
 * configured across hosts (WorkManager enforces a 15-minute floor regardless), and each host is
 * only actually re-checked once its own interval has elapsed (see [HardwareMonitorWorker]).
 */
object MonitorScheduler {

    private const val UNIQUE_WORK_NAME = "hardware_monitor"
    private const val MIN_INTERVAL_MINUTES = 15

    /**
     * Runs the check for one host straight away, ignoring its interval.
     *
     * The periodic work only reports transitions, so waiting for it proves nothing about whether
     * notifications are wired up at all. This is what the settings screen's test button calls.
     */
    fun runNow(context: Context, hostId: String) {
        val request = androidx.work.OneTimeWorkRequestBuilder<HardwareMonitorWorker>()
            .setInputData(androidx.work.workDataOf(HardwareMonitorWorker.KEY_FORCED_HOST_ID to hostId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("hardware_monitor_test", androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    fun reschedule(context: Context, notificationSettings: NotificationSettingsRepository) {
        val enabled = notificationSettings.enabledHosts()
        val workManager = WorkManager.getInstance(context)
        if (enabled.isEmpty()) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val intervalMinutes = enabled.values.minOf { it.intervalMinutes }.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<HardwareMonitorWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
