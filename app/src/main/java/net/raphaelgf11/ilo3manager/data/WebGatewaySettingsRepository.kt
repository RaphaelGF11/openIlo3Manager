package net.raphaelgf11.ilo3manager.data

import android.content.Context
import org.json.JSONObject

/** The Web tab's per-host gateway options, remembered between app launches. */
data class WebGatewaySettings(
    val exposeAllInterfaces: Boolean = false,
    val useHttps: Boolean = false,
    /** null means "let the OS pick a free port". */
    val forcedPort: Int? = null,
)

/**
 * Stores the Web tab's options per host. Plain (unencrypted) preferences: unlike
 * [HostRepository], nothing here is a secret — just which port to bind and whether to expose the
 * gateway beyond loopback.
 */
class WebGatewaySettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("ilo3manager_web_gateway", Context.MODE_PRIVATE)

    fun settingsFor(hostId: String): WebGatewaySettings {
        val json = prefs.getString(hostId, null) ?: return WebGatewaySettings()
        return runCatching {
            val o = JSONObject(json)
            WebGatewaySettings(
                exposeAllInterfaces = o.optBoolean("exposeAllInterfaces", false),
                useHttps = o.optBoolean("useHttps", false),
                forcedPort = if (o.has("forcedPort")) o.getInt("forcedPort") else null,
            )
        }.getOrDefault(WebGatewaySettings())
    }

    fun save(hostId: String, settings: WebGatewaySettings) {
        val o = JSONObject().apply {
            put("exposeAllInterfaces", settings.exposeAllInterfaces)
            put("useHttps", settings.useHttps)
            settings.forcedPort?.let { put("forcedPort", it) }
        }
        prefs.edit().putString(hostId, o.toString()).apply()
    }

    fun clear(hostId: String) {
        prefs.edit().remove(hostId).apply()
    }
}
