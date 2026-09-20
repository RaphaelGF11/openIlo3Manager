package net.raphaelgf11.ilo3manager.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/**
 * Receives the outcome of a [PackageInstaller] session.
 *
 * The interesting case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: the system does not install
 * silently, it hands back an intent that opens its own confirmation screen. Launching it here is
 * what makes the update actually appear to the user.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            PackageInstaller.STATUS_SUCCESS -> Unit // The new version is already starting.

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(
                    context,
                    "Installation impossible" + if (message.isNullOrBlank()) "" else " : $message",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
