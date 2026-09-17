package net.raphaelgf11.ilo3manager

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import net.raphaelgf11.ilo3manager.auth.isBiometricAuthAvailable
import net.raphaelgf11.ilo3manager.auth.showBiometricPrompt
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.NotificationSettingsRepository
import net.raphaelgf11.ilo3manager.data.SettingsRepository
import net.raphaelgf11.ilo3manager.notify.NotificationHelper
import net.raphaelgf11.ilo3manager.ui.AppNavGraph
import net.raphaelgf11.ilo3manager.ui.auth.LockScreen
import net.raphaelgf11.ilo3manager.ui.theme.Ilo3managerTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        NotificationHelper.ensureChannel(applicationContext)
        val repository = HostRepository(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)
        val notificationSettingsRepository = NotificationSettingsRepository(applicationContext)
        net.raphaelgf11.ilo3manager.notify.MonitorScheduler.reschedule(applicationContext, notificationSettingsRepository)
        val biometricRequired = isBiometricAuthAvailable(this)

        setContent {
            var unlocked by remember { mutableStateOf(!biometricRequired) }
            var error by remember { mutableStateOf<String?>(null) }

            fun promptUnlock() {
                error = null
                showBiometricPrompt(
                    activity = this,
                    onSuccess = { unlocked = true },
                    onError = { message -> error = message },
                )
            }

            LaunchedEffect(Unit) {
                if (biometricRequired) promptUnlock()
            }

            // Re-lock the app whenever it leaves the foreground, so credentials
            // stay behind biometrics after task switching or a screen lock.
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP && biometricRequired) {
                        unlocked = false
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            Ilo3managerTheme {
                if (unlocked) {
                    AppNavGraph(
                        repository = repository,
                        settingsRepository = settingsRepository,
                        notificationSettingsRepository = notificationSettingsRepository,
                    )
                } else {
                    LockScreen(errorMessage = error, onUnlockClick = ::promptUnlock)
                }
            }
        }
    }
}
