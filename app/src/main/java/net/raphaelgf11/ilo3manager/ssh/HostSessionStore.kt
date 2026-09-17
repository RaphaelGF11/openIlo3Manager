package net.raphaelgf11.ilo3manager.ssh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ilo.ControlSessionController
import net.raphaelgf11.ilo3manager.ilo.VspSessionController
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-wide registry of per-host SSH sessions. Kept outside of any navigation or
 * Activity/ViewModel scope so that connections (control commands + VSP) stay alive when
 * navigating back to the host list, enabling multiple hosts to be connected at once.
 *
 * Sessions only live as long as the app process does; they are not restored across process
 * death, since that would require a foreground service.
 */
object HostSessionStore {

    private val controlSessions = ConcurrentHashMap<String, ControlSessionController>()
    private val vspSessions = ConcurrentHashMap<String, VspSessionController>()

    fun controlSessionFor(host: SshHost): ControlSessionController =
        controlSessions.getOrPut(host.id) { ControlSessionController(host) }

    fun vspSessionFor(host: SshHost): VspSessionController =
        vspSessions.getOrPut(host.id) { VspSessionController(host) }

    private fun isActive(state: ConnectionState) =
        state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING

    /** True while at least one of the host's sessions (control or VSP) is connected or connecting. */
    fun activeFlow(hostId: String): Flow<Boolean> {
        val control = controlSessions[hostId]
        val vsp = vspSessions[hostId]
        return when {
            control != null && vsp != null ->
                combine(control.connectionState, vsp.connectionState) { a, b -> isActive(a) || isActive(b) }
            control != null -> control.connectionState.map(::isActive)
            vsp != null -> vsp.connectionState.map(::isActive)
            else -> flowOf(false)
        }
    }

    /** Closes a host's sessions without forgetting the host itself. */
    fun disconnectAll(hostId: String) {
        controlSessions[hostId]?.disconnect()
        vspSessions[hostId]?.disconnect()
    }

    fun dropSessionsFor(hostId: String) {
        controlSessions.remove(hostId)?.disconnect()
        vspSessions.remove(hostId)?.disconnect()
    }
}
