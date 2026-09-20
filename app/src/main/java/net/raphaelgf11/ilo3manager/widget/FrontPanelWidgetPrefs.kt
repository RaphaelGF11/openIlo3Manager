package net.raphaelgf11.ilo3manager.widget

import android.content.Context

/**
 * Which host each placed widget shows.
 *
 * Keyed by the widget id rather than stored once, because the same home screen may carry one panel
 * per server.
 */
class FrontPanelWidgetPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ilo3manager_panel_widgets", Context.MODE_PRIVATE)

    fun hostId(widgetId: Int): String? = prefs.getString(key(widgetId), null)

    fun setHostId(widgetId: Int, hostId: String) {
        prefs.edit().putString(key(widgetId), hostId).apply()
    }

    fun forget(widgetId: Int) {
        prefs.edit().remove(key(widgetId)).remove(stampKey(widgetId)).apply()
    }

    /** When this widget last managed a reading, used to avoid re-reading on every unlock. */
    fun lastRefreshAt(widgetId: Int): Long = prefs.getLong(stampKey(widgetId), 0L)

    fun markRefreshed(widgetId: Int, at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(stampKey(widgetId), at).apply()
    }

    private fun key(widgetId: Int) = "widget_$widgetId"

    private fun stampKey(widgetId: Int) = "widget_${widgetId}_refreshed_at"
}
