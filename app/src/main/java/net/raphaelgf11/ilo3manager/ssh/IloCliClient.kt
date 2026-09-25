package net.raphaelgf11.ilo3manager.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.raphaelgf11.ilo3manager.data.SshHost
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Runs discrete iLO CLI commands (`power`, `show ...`) over a single, reused
 * SSH connection: iLO3 only allows a handful of concurrent SSH sessions and
 * a malformed target path can wedge one indefinitely, so commands must be
 * serialized on one connection instead of reconnecting per command.
 */
class IloCliClient {

    private var session: Session? = null
    private val mutex = Mutex()

    val isConnected: Boolean
        get() = session?.isConnected == true

    /**
     * What the other end announced itself as, such as `SSH-2.0-RomSShell_4.62` for an iLO 3.
     *
     * Not used to decide anything — an implementation string is too brittle for that — but worth
     * quoting back when the app has concluded it is not talking to an iLO.
     */
    val serverVersion: String
        get() = session?.serverVersion.orEmpty()

    fun connect(host: SshHost) {
        session = IloSessionFactory.connect(host)
    }

    /**
     * Runs [command] and returns its raw stdout. Throws on timeout or transport failure.
     *
     * A watchdog forcibly disconnects the exec channel after [timeoutMs]: iLO3 can wedge a
     * command (e.g. an invalid target path) so the read must be force-unblocked rather than
     * relying on cooperative cancellation, which blocking I/O does not support.
     */
    suspend fun runCommand(command: String, timeoutMs: Long = 12_000): String = mutex.withLock {
        val activeSession = session ?: error("Session non connectée")
        val channel = activeSession.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.outputStream = null
        val stream = channel.inputStream
        channel.connect(8_000)

        val watchdog = CoroutineScope(Dispatchers.IO).launch {
            delay(timeoutMs)
            if (channel.isConnected) channel.disconnect()
        }

        try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val read = try {
                    stream.read(buffer)
                } catch (e: IOException) {
                    if (!channel.isConnected) throw TimeoutException(command) else throw e
                }
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            watchdog.cancel()
            channel.disconnect()
        }
    }

    class TimeoutException(command: String) : IOException("Commande iLO expirée : $command")

    fun disconnect() {
        session?.disconnect()
        session = null
    }
}
