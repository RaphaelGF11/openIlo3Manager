package net.raphaelgf11.ilo3manager.webgateway

import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Reuses legacy-TLS connections across requests instead of handshaking per resource.
 *
 * A full handshake with this server costs ~1.2 s (RSA key exchange plus 3DES/RC4, measured against
 * a real iLO3), so a page pulling forty CSS/JS/image/JSON resources spent most of its load time in
 * handshakes. iLO3 does honour HTTP/1.1 keep-alive, so connections can be pooled — the same four
 * resources take 4.7 s on one connection versus 7.2 s on four.
 *
 * Pooled connections are only reused for a short window: the server closes idle connections on its
 * own schedule, and a connection handed out after it has been closed fails the request. Callers
 * must therefore report back whether the exchange left the connection in a reusable state (see
 * [release] / [discard]), and should retry once on a fresh connection when a *pooled* one fails.
 */
object LegacyTlsConnectionPool {

    /** Kept well under any plausible server-side idle timeout; page loads are bursts anyway. */
    private const val MAX_IDLE_MS = 5_000L

    /** Bounded so a burst of parallel requests can't leave a large pool of sockets behind. */
    private const val MAX_IDLE_PER_HOST = 6

    private class Entry(val connection: LegacyTlsConnection, val idleSince: Long)

    private val idle = ConcurrentHashMap<String, ArrayDeque<Entry>>()

    /** A connection to [host]:[port], reused if one is available. */
    fun acquire(host: String, port: Int): Pooled {
        val key = "$host:$port"
        val deque = idle.getOrPut(key) { ArrayDeque() }
        val now = System.currentTimeMillis()
        synchronized(deque) {
            while (deque.isNotEmpty()) {
                val entry = deque.removeFirst()
                if (now - entry.idleSince <= MAX_IDLE_MS) {
                    return Pooled(entry.connection, fromPool = true)
                }
                runCatching { entry.connection.close() }
            }
        }
        return Pooled(LegacyTlsHttpClient.connect(host, port), fromPool = false)
    }

    /** Returns a still-healthy connection to the pool for reuse. */
    fun release(host: String, port: Int, connection: LegacyTlsConnection) {
        val key = "$host:$port"
        val deque = idle.getOrPut(key) { ArrayDeque() }
        synchronized(deque) {
            if (deque.size >= MAX_IDLE_PER_HOST) {
                runCatching { connection.close() }
                return
            }
            deque.addLast(Entry(connection, System.currentTimeMillis()))
        }
    }

    fun discard(connection: LegacyTlsConnection) {
        runCatching { connection.close() }
    }

    /** Closes every idle connection; called when a gateway stops. */
    fun evictAll() {
        idle.values.forEach { deque ->
            synchronized(deque) {
                deque.forEach { runCatching { it.connection.close() } }
                deque.clear()
            }
        }
        idle.clear()
    }

    class Pooled(val connection: LegacyTlsConnection, val fromPool: Boolean)
}
