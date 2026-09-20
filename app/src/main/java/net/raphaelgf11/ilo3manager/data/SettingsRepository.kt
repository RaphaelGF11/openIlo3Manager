package net.raphaelgf11.ilo3manager.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ilo3manager_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        AppSettings(
            autoRefreshSeconds = prefs.getInt(KEY_AUTO_REFRESH_SECONDS, 30),
            fasterRefreshOverIpmi = prefs.getBoolean(KEY_IPMI_FASTER_REFRESH, true),
            ipmiRefreshDivider = prefs.getInt(KEY_IPMI_REFRESH_DIVIDER, 3),
        ),
    )
    val settings: StateFlow<AppSettings> = _settings

    /**
     * Whether the update dialog may appear at launch. The settings screen keeps checking either
     * way, so dismissing it hides the prompt without hiding the updates themselves.
     */
    var updateDialogEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPDATE_DIALOG, true)
        set(value) = prefs.edit().putBoolean(KEY_UPDATE_DIALOG, value).apply()

    fun setAutoRefreshSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_AUTO_REFRESH_SECONDS, seconds).apply()
        _settings.value = _settings.value.copy(autoRefreshSeconds = seconds)
    }

    fun setFasterRefreshOverIpmi(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IPMI_FASTER_REFRESH, enabled).apply()
        _settings.value = _settings.value.copy(fasterRefreshOverIpmi = enabled)
    }

    fun setIpmiRefreshDivider(divider: Int) {
        prefs.edit().putInt(KEY_IPMI_REFRESH_DIVIDER, divider).apply()
        _settings.value = _settings.value.copy(ipmiRefreshDivider = divider)
    }

    companion object {
        private const val KEY_AUTO_REFRESH_SECONDS = "auto_refresh_seconds"
        private const val KEY_UPDATE_DIALOG = "update_dialog_enabled"
        private const val KEY_IPMI_FASTER_REFRESH = "ipmi_faster_refresh"
        private const val KEY_IPMI_REFRESH_DIVIDER = "ipmi_refresh_divider"
    }
}
