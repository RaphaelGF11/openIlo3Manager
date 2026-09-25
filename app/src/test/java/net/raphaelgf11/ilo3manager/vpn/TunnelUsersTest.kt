package net.raphaelgf11.ilo3manager.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sharing one tunnel between hosts is the whole point of naming networks, and getting the teardown
 * wrong would cut a working session on another server — a failure the user would see as a random
 * disconnection, with nothing pointing at its cause.
 */
class TunnelUsersTest {

    @Test
    fun `the last user takes the tunnel down`() {
        val users = TunnelUsers()
        users.claim("vpn", "dl380")

        assertEquals(listOf("vpn"), users.release("dl380"))
    }

    @Test
    fun `releasing one host leaves a tunnel another host still uses`() {
        val users = TunnelUsers()
        users.claim("vpn", "dl380")
        users.claim("vpn", "dl360")

        assertTrue("le tunnel sert encore au second serveur", users.release("dl380").isEmpty())
        assertEquals(setOf("dl360"), users.usersOf("vpn"))
        assertEquals(listOf("vpn"), users.release("dl360"))
    }

    @Test
    fun `claiming twice still only needs one release`() {
        // Every endpoint resolution claims, and a host resolves several ports over its lifetime.
        val users = TunnelUsers()
        users.claim("vpn", "dl380")
        users.claim("vpn", "dl380")

        assertEquals(listOf("vpn"), users.release("dl380"))
    }

    @Test
    fun `a host on two networks releases both`() {
        val users = TunnelUsers()
        users.claim("vpn", "dl380")
        users.claim("rebond", "dl380")

        assertEquals(setOf("vpn", "rebond"), users.release("dl380").toSet())
    }

    @Test
    fun `releasing an unknown host closes nothing`() {
        val users = TunnelUsers()
        users.claim("vpn", "dl380")

        assertTrue(users.release("jamais-connecte").isEmpty())
        assertEquals(setOf("dl380"), users.usersOf("vpn"))
    }

    @Test
    fun `a forgotten network is not released a second time`() {
        // Closing outright happens when a network is edited or deleted under a live session; the
        // host disconnecting afterwards must not then report a tunnel to close again.
        val users = TunnelUsers()
        users.claim("vpn", "dl380")
        users.forget("vpn")

        assertTrue(users.release("dl380").isEmpty())
    }
}
