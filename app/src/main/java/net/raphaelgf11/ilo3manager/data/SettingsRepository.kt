package net.raphaelgf11.ilo3manager.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("ilo3manager_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(
        AppSettings(
            autoRefreshSeconds = prefs.getInt(KEY_AUTO_REFRESH_SECONDS, 30),
        ),
    )
    val settings: StateFlow<AppSettings> = _settings

    fun setAutoRefreshSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_AUTO_REFRESH_SECONDS, seconds).apply()
        _settings.value = _settings.value.copy(autoRefreshSeconds = seconds)
    }

    companion object {
        private const val KEY_AUTO_REFRESH_SECONDS = "auto_refresh_seconds"
    }
}
