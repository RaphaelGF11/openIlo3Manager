package net.raphaelgf11.ilo3manager.ilo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ssh.ConnectionState
import net.raphaelgf11.ilo3manager.ssh.SshTerminalSession

/**
 * Owns the dedicated SSH connection used for the Virtual Serial Port tab, separate from the
 * control connection used for power/health commands. Lives in
 * [net.raphaelgf11.ilo3manager.ssh.HostSessionStore], independent of navigation, so the
 * connection survives leaving and returning to the host list.
 */
class VspSessionController(private val host: SshHost) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: SshTerminalSession? = null

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    fun connectIfNeeded() {
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) return
        reconnect()
    }

    fun reconnect() {
        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        val newSession = SshTerminalSession { chunk -> _output.value += chunk }
        session = newSession
        scope.launch {
            try {
                newSession.connect(host, command = "vsp")
                _connectionState.value = ConnectionState.CONNECTED
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Connexion impossible"
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    fun sendCommand(command: String) {
        scope.launch {
            try {
                session?.send(command)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Échec d'envoi de la commande"
            }
        }
    }

    /** Sends the iLO CLI's "ESC (" escape sequence to leave VSP mode, then closes the connection. */
    fun exitVsp() {
        scope.launch {
            try {
                session?.sendRaw(byteArrayOf(0x1B, '('.code.toByte()))
            } catch (_: Exception) {
                // The remote side may already be closing; fall through to disconnect regardless.
            }
            session?.disconnect()
            session = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    fun disconnect() {
        scope.launch {
            session?.disconnect()
            session = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }
}
