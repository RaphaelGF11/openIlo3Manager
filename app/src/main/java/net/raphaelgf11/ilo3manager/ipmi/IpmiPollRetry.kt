package net.raphaelgf11.ilo3manager.ipmi

import java.io.IOException

/** Attempts a whole poll gets before it is reported as failed: the first one plus two retries. */
const val IPMI_POLL_ATTEMPTS = 3

/** Pause between two attempts, long enough for a burst of loss to pass. */
const val IPMI_POLL_RETRY_DELAY_MS = 500L

/**
 * Runs a whole IPMI poll again when it fails.
 *
 * [IpmiLanClient] already retransmits individual datagrams, but that only covers a request lost on
 * the way out. A session the BMC drops halfway, or a reply that arrives just after the socket gave
 * up, fails the poll as a whole — and IPMI being UDP, that happens often enough that a background
 * check reporting the server as unreachable is usually wrong. So the unattended callers, the
 * monitoring worker and the widget, start a fresh session rather than believe the first failure.
 *
 * Deliberately not used on screen: there the user is watching, and a dot that stays grey until they
 * pull to refresh beats twenty seconds of spinner.
 *
 * Inline so the pause can suspend when the caller is a coroutine.
 */
inline fun <T> retryingIpmiPoll(
    attempts: Int = IPMI_POLL_ATTEMPTS,
    pause: (Long) -> Unit = { Thread.sleep(it) },
    block: () -> T,
): T {
    var last: Exception? = null
    for (attempt in 1..attempts) {
        try {
            return block()
        } catch (refused: IpmiRefusedException) {
            // The BMC understood the request and said no. Asking twice more only wastes time.
            throw refused
        } catch (e: Exception) {
            last = e
            if (attempt < attempts) pause(IPMI_POLL_RETRY_DELAY_MS)
        }
    }
    throw last ?: IOException("Aucune tentative IPMI")
}
