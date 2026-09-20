package net.raphaelgf11.ilo3manager.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import net.raphaelgf11.ilo3manager.data.SettingsRepository

/**
 * Keeps the placed panels fresh while the user is actually looking at their home screen.
 *
 * Android exposes no widget-visibility callback, so this polls — but only under two conditions, and
 * both matter. The screen must be on, and the foreground app must be the launcher. Dropping either
 * would mean opening an IPMI session every few seconds for hours: the iLO3 keeps a very small
 * session table, shared with SSH, and exhausting it locks the user out of both.
 *
 * The chain is driven by one-shot alarms that re-arm themselves, and simply stops re-arming once
 * the conditions no longer hold. Nothing runs while the phone is in a pocket.
 */
object PanelRefreshScheduler {

    /** Whatever the user configured, never hammer the BMC faster than this. */
    private const val MIN_INTERVAL_MS = 10_000L

    fun ensureRunning(context: Context) {
        if (!hasWidgets(context)) return
        schedule(context, intervalMs(context))
    }

    fun stop(context: Context) {
        alarmManager(context).cancel(tickIntent(context))
    }

    /**
     * One tick: refresh if it is worth it, then decide whether to keep going.
     *
     * Returns having either re-armed the chain or let it die, so the caller has nothing to decide.
     */
    fun onTick(context: Context) {
        if (!hasWidgets(context) || !screenOn(context)) return
        if (onHomeScreen(context)) refreshAll(context)
        // Re-arm even when the launcher was not in front: the user may come back to it without
        // ever locking the phone, and only an unlock would otherwise restart the chain.
        schedule(context, intervalMs(context))
    }

    private fun refreshAll(context: Context) {
        widgetIds(context).forEach { FrontPanelWidget.requestRefresh(context, it) }
    }

    private fun schedule(context: Context, delayMs: Long) {
        val triggerAt = System.currentTimeMillis() + delayMs
        // A window rather than an exact alarm: exact alarms need a special permission from
        // Android 12, and a panel being a couple of seconds late costs nothing.
        alarmManager(context).setWindow(
            AlarmManager.RTC,
            triggerAt,
            delayMs / 2,
            tickIntent(context),
        )
    }

    private fun intervalMs(context: Context): Long {
        val settings = SettingsRepository(context).settings.value
        // The panel is always read over IPMI, so it follows the IPMI interval.
        return (settings.refreshSecondsFor(usesIpmi = true) * 1_000L).coerceAtLeast(MIN_INTERVAL_MS)
    }

    private fun screenOn(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    /**
     * Whether the launcher is in front.
     *
     * This needs usage access, which the user grants by hand and may well refuse. Without it the
     * honest answer is "unknown", and the safe reading of unknown is *no* — polling a server every
     * ten seconds on a guess is precisely what must not happen.
     */
    private fun onHomeScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return false
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return false
        val now = System.currentTimeMillis()
        val recent = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
        if (recent.isNullOrEmpty()) return false // No permission, or nothing to go on.
        val foreground = recent.maxByOrNull { it.lastTimeUsed }?.packageName ?: return false
        return foreground in launcherPackages(context)
    }

    private fun launcherPackages(context: Context): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }

    /** True when usage access has been granted, which the settings screen offers to request. */
    fun hasUsageAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return false
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return false
        val now = System.currentTimeMillis()
        return !usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now).isNullOrEmpty()
    }

    private fun hasWidgets(context: Context) = widgetIds(context).isNotEmpty()

    private fun widgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, FrontPanelWidget::class.java))

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun tickIntent(context: Context): PendingIntent {
        val intent = Intent(context, PanelTickReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }
}
