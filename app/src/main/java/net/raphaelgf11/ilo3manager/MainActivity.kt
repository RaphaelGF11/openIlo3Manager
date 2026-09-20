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
import net.raphaelgf11.ilo3manager.auth.AppLock
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

    companion object {
        /** Opens straight onto one host; set by the front-panel widget. */
        const val EXTRA_HOST_ID = "host_id"
    }

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
            var unlocked by remember { mutableStateOf(!biometricRequired || AppLock.isUnlocked()) }
            // Only build the navigation graph once the user has authenticated at least once, so
            // host data is never composed behind the lock on a cold start.
            var unlockedOnce by remember { mutableStateOf(unlocked) }
            var error by remember { mutableStateOf<String?>(null) }

            fun promptUnlock() {
                error = null
                showBiometricPrompt(
                    activity = this,
                    onSuccess = {
                        AppLock.markUnlocked()
                        unlocked = true
                        unlockedOnce = true
                    },
                    onError = { message -> error = message },
                )
            }

            LaunchedEffect(Unit) {
                if (biometricRequired && !unlocked) promptUnlock()
            }

            // Credentials stay behind biometrics after a real task switch or a screen lock, but
            // not when the app itself opens a picker or the QR scanner — see [AppLock].
            DisposableEffect(Unit) {
                val observer = LifecycleEventObserver { _, event ->
                    if (!biometricRequired) return@LifecycleEventObserver
                    when (event) {
                        Lifecycle.Event.ON_STOP -> AppLock.markBackgrounded()
                        Lifecycle.Event.ON_START -> {
                            unlocked = AppLock.isUnlocked()
                            if (!unlocked) promptUnlock()
                        }
                        else -> Unit
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            Ilo3managerTheme {
                // The navigation graph stays composed behind the lock screen rather than being
                // replaced by it: unlocking then returns to the screen the user was on, instead of
                // rebuilding the app from the host list and losing where they were.
                if (unlockedOnce) {
                    AppNavGraph(
                        repository = repository,
                        settingsRepository = settingsRepository,
                        notificationSettingsRepository = notificationSettingsRepository,
                        initialHostId = intent?.getStringExtra(EXTRA_HOST_ID),
                    )
                }
                if (!unlocked) {
                    LockScreen(errorMessage = error, onUnlockClick = ::promptUnlock)
                }
            }
        }
    }
}
