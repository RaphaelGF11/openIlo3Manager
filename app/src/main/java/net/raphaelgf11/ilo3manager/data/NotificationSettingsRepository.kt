package net.raphaelgf11.ilo3manager.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.raphaelgf11.ilo3manager.notify.MonitorScheduler
import org.json.JSONObject

/**
 * Per-host notification preferences (interval + which events to alert on). Plain, unencrypted
 * storage since none of this is a secret, unlike [HostRepository].
 */
class NotificationSettingsRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("ilo3manager_notif_settings", Context.MODE_PRIVATE)

    private val _settingsByHost = MutableStateFlow(loadAll())
    val settingsByHost: StateFlow<Map<String, HostNotificationSettings>> = _settingsByHost

    fun settingsFor(hostId: String): HostNotificationSettings = _settingsByHost.value[hostId] ?: HostNotificationSettings()

    fun save(hostId: String, settings: HostNotificationSettings) {
        val updated = _settingsByHost.value + (hostId to settings)
        _settingsByHost.value = updated
        persist(updated)
        MonitorScheduler.reschedule(appContext, this)
    }

    fun enabledHosts(): Map<String, HostNotificationSettings> = _settingsByHost.value.filterValues { it.enabled }

    private fun persist(all: Map<String, HostNotificationSettings>) {
        val root = JSONObject()
        all.forEach { (hostId, settings) ->
            root.put(
                hostId,
                JSONObject().apply {
                    put("enabled", settings.enabled)
                    put("intervalMinutes", settings.intervalMinutes)
                    put("notifyOnConnectionFailure", settings.notifyOnConnectionFailure)
                    put("notifyOnDegraded", settings.notifyOnDegraded)
                    put("notifyOnCritical", settings.notifyOnCritical)
                    put("notifyOnPoweredOff", settings.notifyOnPoweredOff)
                    put("notifyOnPoweredOn", settings.notifyOnPoweredOn)
                },
            )
        }
        prefs.edit().putString(KEY_JSON, root.toString()).apply()
    }

    private fun loadAll(): Map<String, HostNotificationSettings> {
        val json = prefs.getString(KEY_JSON, null) ?: return emptyMap()
        val root = JSONObject(json)
        val result = LinkedHashMap<String, HostNotificationSettings>()
        root.keys().forEach { hostId ->
            val o = root.getJSONObject(hostId)
            result[hostId] = HostNotificationSettings(
                enabled = o.optBoolean("enabled", false),
                intervalMinutes = o.optInt("intervalMinutes", 15),
                notifyOnConnectionFailure = o.optBoolean("notifyOnConnectionFailure", true),
                notifyOnDegraded = o.optBoolean("notifyOnDegraded", true),
                notifyOnCritical = o.optBoolean("notifyOnCritical", true),
                notifyOnPoweredOff = o.optBoolean("notifyOnPoweredOff", true),
                notifyOnPoweredOn = o.optBoolean("notifyOnPoweredOn", false),
            )
        }
        return result
    }

    companion object {
        private const val KEY_JSON = "settings_json"
    }
}
