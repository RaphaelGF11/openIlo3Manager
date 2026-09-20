package net.raphaelgf11.ilo3manager.ilo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Reports what a connection is currently doing, per host.
 *
 * Connecting spans several layers — tunnel, then SSH, then the first reads — each implemented in a
 * different class, and the slow ones are the ones deepest down. A shared channel keyed by host
 * lets each layer say where it is without threading a callback through every signature.
 */
object ConnectionProgress {

    private val flows = ConcurrentHashMap<String, MutableStateFlow<String?>>()

    fun flowFor(hostId: String): StateFlow<String?> = flowOf(hostId)

    fun report(hostId: String, message: String?) {
        flowOf(hostId).value = message
    }

    fun clear(hostId: String) = report(hostId, null)

    private fun flowOf(hostId: String): MutableStateFlow<String?> =
        flows.getOrPut(hostId) { MutableStateFlow(null) }
}
