package net.raphaelgf11.ilo3manager.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import net.raphaelgf11.ilo3manager.R
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.InstantAlertMode
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the SNMP trap listeners open for as long as the user wants instant alerts.
 *
 * A foreground service because that is the only way Android keeps a socket alive: the whole point
 * of this mode is to hear about an incident between two periodic checks, which a process the system
 * is free to kill cannot do.
 *
 * One listener per host rather than one shared socket. The relay that carries traffic in from the
 * tunnel presents itself as loopback, so the sender's address says nothing about which server sent
 * what — but the port it arrives on does, once each server has its own.
 */
class TrapReceiverService : Service() {

    private class Watcher(val listener: SnmpTrapListener, val thread: Thread)

    private val watchers = ConcurrentHashMap<String, Watcher>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(NOTIFICATION_ID, statusNotification("Démarrage…"))
        rebuild()
        // Restarted if the system kills it: the socket is the feature.
        return START_STICKY
    }

    override fun onDestroy() {
        watchers.values.forEach { it.listener.close() }
        watchers.clear()
        super.onDestroy()
    }

    /** Opens a listener for every host asking for direct traps, and drops the others. */
    private fun rebuild() {
        val wanted = HostRepository(this).getHosts()
            .filter { it.instantAlertMode == InstantAlertMode.SNMP_DIRECT }

        watchers.keys.filterNot { id -> wanted.any { it.id == id } }.forEach { id ->
            watchers.remove(id)?.listener?.close()
        }

        val failures = mutableListOf<String>()
        wanted.forEach { host ->
            if (watchers.containsKey(host.id)) return@forEach
            open(host).onFailure { failures.add("${host.name} : ${it.message}") }
        }

        val listening = watchers.size
        val text = when {
            listening == 0 && failures.isEmpty() -> "Aucun serveur en alerte instantanée"
            failures.isEmpty() -> "À l'écoute des alertes de $listening serveur(s)"
            else -> "À l'écoute de $listening serveur(s) ; ${failures.size} en échec"
        }
        notificationManager()?.notify(NOTIFICATION_ID, statusNotification(text))
    }

    private fun open(host: SshHost): Result<Unit> = runCatching {
        val listener = SnmpTrapListener(
            onTrap = { trap -> report(host, trap) },
            // Logged as a count, never as content: this is unauthenticated traffic and its bytes
            // have no business in a log that gets shared.
            onUndecodable = { _, size ->
                android.util.Log.d("TrapReceiver", "datagramme illisible (${size} octets)")
            },
        )
        HostTunnelManager
            .listenInTunnel(host, SnmpTrapListener.SNMP_TRAP_PORT, listener.port)
            .onFailure {
                listener.close()
                throw it
            }
        val thread = Thread(listener::listen, "traps-${host.id}").apply {
            isDaemon = true
            start()
        }
        watchers[host.id] = Watcher(listener, thread)
    }

    private fun report(host: SshHost, trap: SnmpTrap) {
        val summary = describeTrap(trap, host.name)
        // Distinct from the status notification, and one per host so a second server's alert does
        // not replace the first.
        NotificationHelper.notify(
            this,
            notificationId = ALERT_ID_BASE + host.id.hashCode().and(0xFFFF),
            title = summary.title,
            message = summary.detail,
        )
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            STATUS_CHANNEL_ID,
            "Écoute des alertes",
            // Low: this one only says the listener is up. The alerts themselves go to the
            // hardware-alert channel, which is where the user wants to be interrupted.
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager()?.createNotificationChannel(channel)
        NotificationHelper.ensureChannel(this)
    }

    private fun statusNotification(text: String): Notification =
        NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setContentTitle("Alertes instantanées")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

    companion object {
        private const val STATUS_CHANNEL_ID = "ilo3_trap_listener"
        private const val NOTIFICATION_ID = 4201
        private const val ALERT_ID_BASE = 4300

        /**
         * Starts or refreshes the service, and stops it when no host wants it.
         *
         * Called whenever a host is saved, so switching the mode off actually releases the socket
         * and the notification rather than leaving a service running for nothing.
         */
        fun sync(context: Context) {
            val wanted = runCatching {
                HostRepository(context).getHosts()
                    .any { it.instantAlertMode == InstantAlertMode.SNMP_DIRECT }
            }.getOrDefault(false)

            val intent = Intent(context, TrapReceiverService::class.java)
            if (wanted) {
                context.startForegroundService(intent)
            } else {
                context.stopService(intent)
            }
        }
    }
}
