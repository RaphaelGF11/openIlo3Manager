package net.raphaelgf11.ilo3manager.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These values come from a text field and are handed to a root shell, so what they are allowed to
 * contain is the whole of the safety here.
 */
class SecondaryAddressTest {

    @Test
    fun `a well-formed interface and address produce the command`() {
        assertEquals(
            "ip addr add 192.168.1.9/24 dev wlan0",
            secondaryAddressAddCommand("wlan0", "192.168.1.9/24"),
        )
        assertEquals(
            "ip addr del 192.168.1.9/24 dev wlan0",
            secondaryAddressRemoveCommand("wlan0", "192.168.1.9/24"),
        )
    }

    @Test
    fun `anything a shell would read as syntax is refused, not quoted`() {
        // This string reaches `su -c`. Quoting it would be one mistake away from running it.
        listOf(
            "wlan0; rm -rf /",
            "wlan0 && reboot",
            "wlan0\$(id)",
            "wlan0`id`",
            "wlan0|sh",
            "wlan0 wlan1",
            "",
        ).forEach { hostile ->
            assertFalse("« $hostile » doit être refusé", isValidInterfaceName(hostile))
            assertNull(secondaryAddressAddCommand(hostile, "192.168.1.9/24"))
        }
    }

    @Test
    fun `an address that is not one is refused too`() {
        listOf(
            "192.168.1.9",           // no prefix: ip would guess one
            "192.168.1.9/33",        // impossible prefix
            "192.168.1.256/24",      // octet out of range
            "192.168.1.9/24; reboot",
            "not-an-address",
            "",
        ).forEach { bad ->
            assertFalse("« $bad » doit être refusé", isValidCidr(bad))
            assertNull(secondaryAddressAddCommand("wlan0", bad))
        }
    }

    @Test
    fun `ordinary addresses and prefixes are accepted`() {
        listOf("10.0.0.1/8", "192.168.1.9/24", "172.16.0.1/12", "0.0.0.0/0", "255.255.255.255/32")
            .forEach { assertTrue("« $it » doit être accepté", isValidCidr(it)) }
        listOf("wlan0", "eth0", "rmnet_data0", "ap-br0").forEach {
            assertTrue("« $it » doit être accepté", isValidInterfaceName(it))
        }
    }

    /** Verbatim `ip -4 addr show` from the Android x86 test machine. */
    private val ipOutput = """
        1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN group default qlen 1000
            inet 127.0.0.1/8 scope host lo
        5: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc pfifo_fast state UP group default
            inet 192.168.1.72/24 brd 192.168.1.255 scope global wlan0
            inet 192.168.1.99/24 scope global secondary wlan0
    """.trimIndent()

    @Test
    fun `an address already configured is recognised`() {
        assertTrue(outputHasAddress(ipOutput, "192.168.1.99/24"))
        assertTrue(outputHasAddress(ipOutput, "192.168.1.72/24"))
        assertFalse(outputHasAddress(ipOutput, "192.168.1.98/24"))
    }

    @Test
    fun `the same address with another prefix counts as absent`() {
        // It would route differently, which is the only reason to configure one.
        assertFalse(outputHasAddress(ipOutput, "192.168.1.99/16"))
    }

    @Test
    fun `adding an address that is already there is not a failure`() {
        assertTrue(addFailureIsAlreadyPresent("RTNETLINK answers: File exists"))
        assertFalse(addFailureIsAlreadyPresent("RTNETLINK answers: Operation not permitted"))
        assertFalse(addFailureIsAlreadyPresent("su: not found"))
    }
}
