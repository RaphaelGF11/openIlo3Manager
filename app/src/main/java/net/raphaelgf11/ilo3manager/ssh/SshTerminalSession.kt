package net.raphaelgf11.ilo3manager.ssh

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.raphaelgf11.ilo3manager.data.SshHost
import java.io.OutputStream

/**
 * A single interactive PTY channel over a dedicated SSH connection (console / VSP).
 *
 * When [command] is given, it is run directly as the SSH command (like `ssh host vsp`) via an
 * "exec" channel with a PTY attached, instead of opening a plain shell and typing the command
 * into it — the latter leaves the shell's own banner/prompt mixed into the VSP output.
 */
class SshTerminalSession(private val onOutput: (String) -> Unit) {

    private var session: Session? = null
    private var channel: Channel? = null
    private var output: OutputStream? = null

    val isConnected: Boolean
        get() = session?.isConnected == true

    fun connect(host: SshHost, command: String? = null) {
        val newSession = IloSessionFactory.connect(host)

        val newChannel: Channel
        val channelOutput: OutputStream
        val channelInput: java.io.InputStream

        if (command != null) {
            val exec = newSession.openChannel("exec") as ChannelExec
            exec.setCommand(command)
            exec.setPty(true)
            exec.setPtyType("vt100")
            channelInput = exec.inputStream
            channelOutput = exec.outputStream
            exec.connect(10_000)
            newChannel = exec
        } else {
            val shell = newSession.openChannel("shell") as ChannelShell
            shell.setPtyType("vt100")
            channelInput = shell.inputStream
            channelOutput = shell.outputStream
            shell.connect(10_000)
            newChannel = shell
        }

        session = newSession
        channel = newChannel
        output = channelOutput

        CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(4096)
            try {
                while (newChannel.isConnected) {
                    val read = channelInput.read(buffer)
                    if (read < 0) break
                    if (read > 0) onOutput(String(buffer, 0, read, Charsets.UTF_8))
                }
            } catch (_: Exception) {
                // Stream closed on disconnect; nothing to surface.
            }
        }
    }

    fun send(text: String) {
        output?.write((text + "\n").toByteArray(Charsets.UTF_8))
        output?.flush()
    }

    fun sendRaw(bytes: ByteArray) {
        output?.write(bytes)
        output?.flush()
    }

    fun disconnect() {
        channel?.disconnect()
        session?.disconnect()
        channel = null
        session = null
        output = null
    }
}
