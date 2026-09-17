package net.raphaelgf11.ilo3manager.notify

import android.content.Context
import org.json.JSONObject

data class HostMonitorState(
    val lastCheckedAtMillis: Long = 0,
    val connectionOk: Boolean? = null,
    val powerState: String = "UNKNOWN",
    val health: String = "UNKNOWN",
)

/**
 * Internal bookkeeping for [HardwareMonitorWorker]: when a host was last checked and what it
 * last reported, so the worker can detect *transitions* (e.g. OK -> CRITICAL) instead of
 * re-notifying on every periodic run while a problem persists.
 */
class MonitorStateStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ilo3manager_monitor_state", Context.MODE_PRIVATE)

    fun get(hostId: String): HostMonitorState {
        val json = prefs.getString(hostId, null) ?: return HostMonitorState()
        val o = JSONObject(json)
        return HostMonitorState(
            lastCheckedAtMillis = o.optLong("lastCheckedAtMillis", 0),
            connectionOk = if (o.has("connectionOk")) o.optBoolean("connectionOk") else null,
            powerState = o.optString("powerState", "UNKNOWN"),
            health = o.optString("health", "UNKNOWN"),
        )
    }

    fun set(hostId: String, state: HostMonitorState) {
        val o = JSONObject().apply {
            put("lastCheckedAtMillis", state.lastCheckedAtMillis)
            if (state.connectionOk != null) put("connectionOk", state.connectionOk)
            put("powerState", state.powerState)
            put("health", state.health)
        }
        prefs.edit().putString(hostId, o.toString()).apply()
    }
}
