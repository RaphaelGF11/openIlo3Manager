package net.raphaelgf11.ilo3manager.webgateway

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import net.raphaelgf11.ilo3manager.R

private const val CHANNEL_ID = "ilo3_web_gateway"
private const val NOTIFICATION_ID = 4201

/**
 * A minimal foreground service whose only job is to keep the app process alive while at least
 * one local HTTPS-to-legacy-TLS gateway ([IloHttpProxyServer]) is running, since the user is
 * expected to switch away to a browser to actually use it.
 */
class WebGatewayForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Passerelle web iLO active")
            .setContentText("Une interface web iLO est accessible localement.")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Passerelle web iLO", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        fun intent(context: Context) = Intent(context, WebGatewayForegroundService::class.java)
    }
}
