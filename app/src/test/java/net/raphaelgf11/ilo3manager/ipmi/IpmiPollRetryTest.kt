package net.raphaelgf11.ilo3manager.ipmi

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The retry is what stands between a lost datagram and a false "serveur injoignable" notification,
 * so its exact shape matters: too few attempts and the alarm still fires, too many and a genuinely
 * wrong password gets replayed against an iLO that locks accounts out.
 */
class IpmiPollRetryTest {

    @Test
    fun `a poll that works is run once`() {
        var calls = 0
        val pauses = mutableListOf<Long>()

        val result = retryingIpmiPoll(pause = { pauses += it }) {
            calls++
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(1, calls)
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun `a poll lost twice still succeeds on the third try`() {
        var calls = 0
        val pauses = mutableListOf<Long>()

        val result = retryingIpmiPoll(pause = { pauses += it }) {
            calls++
            if (calls < 3) throw SocketTimeoutException("silence")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(listOf(IPMI_POLL_RETRY_DELAY_MS, IPMI_POLL_RETRY_DELAY_MS), pauses)
    }

    @Test
    fun `three failures give up, and report the last one`() {
        var calls = 0
        val pauses = mutableListOf<Long>()

        val thrown = assertThrows(IOException::class.java) {
            retryingIpmiPoll(pause = { pauses += it }) {
                calls++
                throw IOException("échec $calls")
            }
        }

        assertEquals("échec 3", thrown.message)
        assertEquals(IPMI_POLL_ATTEMPTS, calls)
        // No pause after the final attempt: nothing is waiting on it.
        assertEquals(2, pauses.size)
    }

    @Test
    fun `a refused account is not tried again`() {
        var calls = 0

        val thrown = assertThrows(IpmiRefusedException::class.java) {
            retryingIpmiPoll(pause = { throw AssertionError("ne doit pas patienter") }) {
                calls++
                throw IpmiRefusedException("identifiants incorrects")
            }
        }

        assertEquals("identifiants incorrects", thrown.message)
        assertEquals(1, calls)
    }

    @Test
    fun `a refusal after a timeout still stops the loop`() {
        var calls = 0

        assertThrows(IpmiRefusedException::class.java) {
            retryingIpmiPoll(pause = {}) {
                calls++
                if (calls == 1) throw SocketTimeoutException("silence")
                throw IpmiRefusedException("utilisateur inconnu")
            }
        }

        assertEquals(2, calls)
    }
}
