package net.raphaelgf11.ilo3manager.notify

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Holds a UDP socket open and hands decoded traps to a callback.
 *
 * Deliberately knows nothing about how the datagrams reach it. The port an SNMP trap arrives on is
 * 162, which Android will not let an application bind — so something else has to put the traffic in
 * front of this socket, and there is more than one way to do that: the WireGuard tunnel can listen
 * on 162 inside its own userspace stack and relay here, or a rooted phone can redirect the port.
 * Keeping the socket separate from that choice is what lets both work, and lets this be tested by
 * sending it a datagram directly.
 */
class SnmpTrapListener(
    /** 0 asks the system for any free port, which is what the relay is then pointed at. */
    requestedPort: Int = 0,
    private val onTrap: (SnmpTrap) -> Unit,
    private val onUndecodable: (senderAddress: String, size: Int) -> Unit = { _, _ -> },
) : Closeable {

    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", requestedPort))
    }

    /** The port actually bound, which is what a relay must forward to. */
    val port: Int get() = socket.localPort

    @Volatile
    private var running = true

    /**
     * Reads until closed. Blocking, so callers run it on their own thread.
     *
     * One buffer for the lifetime of the loop rather than one per datagram: traps arrive rarely but
     * in bursts when a machine is in trouble, which is exactly when the phone should not be
     * allocating. The decoder is told how many bytes are live, so the tail of the previous packet
     * is never read as part of this one.
     */
    fun listen() {
        val buffer = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running) {
            packet.setData(buffer, 0, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                // Closing the socket from another thread lands here; anything else is transient.
                if (!running) return
                continue
            }
            val trap = decodeSnmpTrap(buffer, packet.length)
            if (trap != null) {
                // A callback that throws must not take the listener down with it: the next trap is
                // the one that might matter.
                runCatching { onTrap(trap) }
            } else {
                runCatching { onUndecodable(packet.address?.hostAddress.orEmpty(), packet.length) }
            }
        }
    }

    override fun close() {
        running = false
        runCatching { socket.close() }
    }

    companion object {
        /** Beyond this a datagram is not a trap; the decoder refuses them anyway. */
        const val MAX_DATAGRAM = 8 * 1024

        /** The port SNMP traps are sent to, which no Android application may bind. */
        const val SNMP_TRAP_PORT = 162
    }
}
