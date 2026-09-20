package net.raphaelgf11.ilo3manager.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.MainActivity
import net.raphaelgf11.ilo3manager.R
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager
import java.text.DateFormat
import java.util.Date

/**
 * Takes one IPMI reading and pushes the rendered panel to the widget.
 *
 * When the reading fails the panel is drawn dark rather than left stale: a lit picture of a server
 * that may since have stopped answering is worse than an honest blank one.
 */
class FrontPanelUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = AppWidgetManager.getInstance(applicationContext)
        val requested = inputData.getInt(FrontPanelWidget.KEY_WIDGET_ID, -1)
        val widgetIds = if (requested > 0) {
            intArrayOf(requested)
        } else {
            manager.getAppWidgetIds(ComponentName(applicationContext, FrontPanelWidget::class.java))
        }

        val hosts = HostRepository(applicationContext).getHosts()
        val prefs = FrontPanelWidgetPrefs(applicationContext)

        widgetIds.forEach { widgetId ->
            val host = prefs.hostId(widgetId)?.let { id -> hosts.firstOrNull { it.id == id } }
            update(manager, widgetId, host)
            prefs.markRefreshed(widgetId)
        }
        return Result.success()
    }

    private suspend fun update(manager: AppWidgetManager, widgetId: Int, host: SshHost?) {
        val (state, caption) = when {
            host == null -> PanelState.DARK to "Widget non configuré"
            !host.ipmiEnabled -> PanelState.DARK to "${host.name} — IPMI désactivé"
            !HostTunnelManager.supportsUdp(host) ->
                PanelState.DARK to "${host.name} — le tunnel ne porte pas l'UDP"
            else -> readPanel(host)
        }

        val views = RemoteViews(applicationContext.packageName, R.layout.widget_front_panel)
        views.setImageViewBitmap(
            R.id.panel,
            FrontPanelRenderer.render(state, widthPx = bitmapWidth(manager, widgetId)),
        )
        views.setTextViewText(R.id.caption, caption)
        views.setOnClickPendingIntent(R.id.panel, openHostIntent(widgetId, host))
        views.setOnClickPendingIntent(R.id.refresh, refreshIntent(widgetId))
        manager.updateAppWidget(widgetId, views)
    }

    private suspend fun readPanel(host: SshHost): Pair<PanelState, String> =
        withContext(Dispatchers.IO) {
            val clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            runCatching { PanelReader.read(host) }
                .map { it to "${host.name} — $clock" }
                .getOrElse { failure ->
                    PanelState.DARK to "${host.name} — injoignable ($clock)"
                }
        }

    /**
     * Renders for the space the launcher actually gave the widget, clamped at both ends: too small
     * and the silkscreen turns to mush, too large and the bitmap crossing to the launcher costs
     * more than the detail is worth.
     */
    private fun bitmapWidth(manager: AppWidgetManager, widgetId: Int): Int {
        val options = manager.getAppWidgetOptions(widgetId)
        val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val density = applicationContext.resources.displayMetrics.density
        val pixels = (widthDp * density).toInt()
        return pixels.coerceIn(480, 1100)
    }

    /** Tapping the panel opens the server it belongs to, not merely the app. */
    private fun openHostIntent(widgetId: Int, host: SshHost?): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (host != null) intent.putExtra(MainActivity.EXTRA_HOST_ID, host.id)
        // A distinct request code per widget, or several panels would share one cached intent and
        // every tap would open whichever server was configured first.
        return PendingIntent.getActivity(applicationContext, widgetId, intent, pendingIntentFlags())
    }

    private fun refreshIntent(widgetId: Int): PendingIntent {
        val intent = Intent(applicationContext, FrontPanelWidget::class.java)
            .setAction(FrontPanelWidget.ACTION_REFRESH)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        return PendingIntent.getBroadcast(
            applicationContext,
            // Offset so it cannot collide with the panel's own request code.
            widgetId + REFRESH_REQUEST_OFFSET,
            intent,
            pendingIntentFlags(),
        )
    }

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    private companion object {
        const val REFRESH_REQUEST_OFFSET = 1_000_000
    }
}
