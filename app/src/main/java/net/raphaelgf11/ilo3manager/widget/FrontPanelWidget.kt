package net.raphaelgf11.ilo3manager.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Home-screen panel for one server.
 *
 * The drawing itself happens in a worker rather than here: a provider's broadcast has seconds to
 * return, and opening an IPMI session over a tunnel can take longer than that.
 */
class FrontPanelWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { requestRefresh(context, it) }
    }

    override fun onEnabled(context: Context) {
        schedulePeriodicRefresh(context)
        PanelRefreshScheduler.ensureRunning(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        PanelRefreshScheduler.stop(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = FrontPanelWidgetPrefs(context)
        appWidgetIds.forEach(prefs::forget)
    }

    /**
     * Also refresh when the widget is resized: the bitmap is rendered for a given pixel width, so a
     * stretched panel would otherwise stay blurry until the next scheduled update.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        requestRefresh(context, appWidgetId)
    }

    companion object {
        private const val PERIODIC_WORK = "front_panel_refresh"
        const val KEY_WIDGET_ID = "widget_id"

        fun requestRefresh(context: Context, widgetId: Int) {
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val request = OneTimeWorkRequestBuilder<FrontPanelUpdateWorker>()
                .setInputData(workDataOf(KEY_WIDGET_ID to widgetId))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("front_panel_$widgetId", ExistingWorkPolicy.REPLACE, request)
            schedulePeriodicRefresh(context)
        }

        /**
         * Half-hourly. Each tick costs a fresh IPMI session per widget, and a panel on the home
         * screen is glanced at, not watched: polling harder would spend the battery on a picture
         * nobody is looking at.
         */
        private fun schedulePeriodicRefresh(context: Context) {
            val request = PeriodicWorkRequestBuilder<FrontPanelUpdateWorker>(30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
