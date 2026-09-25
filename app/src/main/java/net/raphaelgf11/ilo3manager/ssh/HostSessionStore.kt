package net.raphaelgf11.ilo3manager.ssh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager
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

    /**
     * Bumped whenever a session is created or dropped.
     *
     * Observers used to read the maps once and keep that snapshot: a screen composed before a
     * session existed held a null forever and never reflected the session that appeared
     * afterwards. Collecting this lets them re-read at the right moment.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    fun controlSessionFor(host: SshHost): ControlSessionController =
        controlSessions.getOrPut(host.id) { ControlSessionController(host).also { _revision.value++ } }

    /** The live control session for a host, or null — unlike [controlSessionFor], creates nothing. */
    fun existingControlSessionFor(hostId: String): ControlSessionController? = controlSessions[hostId]

    fun vspSessionFor(host: SshHost): VspSessionController =
        vspSessions.getOrPut(host.id) { VspSessionController(host).also { _revision.value++ } }

    /** Pushes an edited host record into any live session, so changes apply without a restart. */
    fun updateHost(host: SshHost) {
        controlSessions[host.id]?.updateHost(host)
    }

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
        // The tunnel outlives the sessions it carries, so it has to be released too — and only
        // goes down if no other host is behind the same network.
        HostTunnelManager.releaseHost(hostId)
    }

    fun dropSessionsFor(hostId: String) {
        controlSessions.remove(hostId)?.disconnect()
        vspSessions.remove(hostId)?.disconnect()
        HostTunnelManager.releaseHost(hostId)
        _revision.value++
    }
}
