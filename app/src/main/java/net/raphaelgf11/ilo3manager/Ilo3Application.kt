package net.raphaelgf11.ilo3manager

import android.app.Application
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.NetworkRepository
import net.raphaelgf11.ilo3manager.notify.TrapReceiverService
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager

/**
 * Process-wide setup that must happen before anything connects.
 *
 * It lives here rather than in `MainActivity` because the activity is not the only way into the
 * process: the front-panel widget and the monitoring worker both start it on their own, with no
 * screen, and they open tunnels exactly like the UI does.
 */
class Ilo3Application : Application() {

    override fun onCreate() {
        super.onCreate()
        val hosts = HostRepository(this)
        val networks = NetworkRepository(this)

        // Runs on every start and does nothing once there is nothing left to move. Done before the
        // lookup below is installed, so no connection can observe a half-migrated host.
        networks.migrateLegacyVpn(hosts)

        // Read on each resolution rather than captured, so an edited network takes effect on the
        // next connection.
        HostTunnelManager.useNetworks { networks.getNetworks() }

        // Started here rather than from a screen: a process brought up by the widget or by the
        // monitoring worker should also be listening, and this is the one place they share.
        runCatching { TrapReceiverService.sync(this) }
    }
}
