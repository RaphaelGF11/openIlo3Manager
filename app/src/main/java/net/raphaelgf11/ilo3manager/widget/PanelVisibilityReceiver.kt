package net.raphaelgf11.ilo3manager.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import net.raphaelgf11.ilo3manager.data.SettingsRepository

/**
 * Refreshes the panels when the user unlocks the device.
 *
 * Android offers no "this widget is on screen" callback, and the framework's own widget period is
 * floored at thirty minutes. Unlocking is the closest honest proxy for the user being about to look
 * at their home screen, so each unlock re-reads any panel older than the configured refresh
 * interval — and leaves the rest alone, because every reading costs an IPMI session.
 */
class PanelVisibilityReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return

        val manager = AppWidgetManager.getInstance(context)
        val widgetIds = manager.getAppWidgetIds(
            ComponentName(context, FrontPanelWidget::class.java),
        )
        if (widgetIds.isEmpty()) return

        val settings = SettingsRepository(context).settings.value
        // The panel is always read over IPMI, so it follows the IPMI interval.
        val staleAfterMs = settings.refreshSecondsFor(usesIpmi = true) * 1_000L
        val prefs = FrontPanelWidgetPrefs(context)
        val now = System.currentTimeMillis()

        widgetIds
            .filter { now - prefs.lastRefreshAt(it) >= staleAfterMs }
            .forEach { FrontPanelWidget.requestRefresh(context, it) }

        // The user is now in front of the device, so start watching for them to reach the home
        // screen; the chain stops by itself once the screen goes off.
        PanelRefreshScheduler.ensureRunning(context)
    }
}
