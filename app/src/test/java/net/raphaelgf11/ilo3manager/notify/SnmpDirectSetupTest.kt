package net.raphaelgf11.ilo3manager.notify

import net.raphaelgf11.ilo3manager.data.NetworkConfig
import net.raphaelgf11.ilo3manager.data.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These rules decide what gets written to the user's BMC. The output samples are real ones, taken
 * from the DL380 G7's iLO 3 at firmware 1.94 rather than imagined from documentation.
 */
class SnmpDirectSetupTest {

    /** Verbatim `show /map1/snmp1`, with one destination already configured. */
    private val showOutput = """
        show /map1/snmp1
        status=0
        status_tag=COMMAND COMPLETED

        /map1/snmp1
          Targets
          Properties
            accessinfo1=127.0.0.1
            accessinfo2=0
            accessinfo3=0
            oemhp_iloalert=yes
            oemhp_agentalert=no
            oemhp_snmppassthru=yes
          Verbs
            cd version exit show set
    """.trimIndent()

    @Test
    fun `the destinations are read in slot order`() {
        assertEquals(listOf("127.0.0.1", "0", "0"), parseSnmpDestinations(showOutput))
    }

    @Test
    fun `iLO alerts being enabled is read out of the same output`() {
        assertTrue(parseIloAlertsEnabled(showOutput))
        assertFalse(parseIloAlertsEnabled(showOutput.replace("oemhp_iloalert=yes", "oemhp_iloalert=no")))
    }

    @Test
    fun `a missing property reads as blank rather than shifting the slots`() {
        val partial = showOutput.replace("    accessinfo2=0\n", "")
        assertEquals(listOf("127.0.0.1", "", "0"), parseSnmpDestinations(partial))
    }

    @Test
    fun `the first free slot is chosen, leaving the occupied one alone`() {
        val choice = chooseSnmpSlot(listOf("127.0.0.1", "0", "0"), "10.0.0.2")
        assertEquals(SnmpSlotChoice.Free(2), choice)
    }

    @Test
    fun `an unset slot is recognised whether it reads 0, 0_0_0_0 or blank`() {
        assertEquals(SnmpSlotChoice.Free(1), chooseSnmpSlot(listOf("0", "x", "y"), "d"))
        assertEquals(SnmpSlotChoice.Free(1), chooseSnmpSlot(listOf("0.0.0.0", "x", "y"), "d"))
        assertEquals(SnmpSlotChoice.Free(1), chooseSnmpSlot(listOf("", "x", "y"), "d"))
    }

    @Test
    fun `configuring twice does not consume a second slot`() {
        val choice = chooseSnmpSlot(listOf("127.0.0.1", "10.0.0.2", "0"), "10.0.0.2")
        assertEquals(SnmpSlotChoice.AlreadySet(2), choice)
    }

    @Test
    fun `a full set of destinations is refused rather than overwritten`() {
        // Those other slots may be a monitoring system the user runs; clobbering one to make room
        // would break it invisibly.
        val choice = chooseSnmpSlot(listOf("10.0.0.1", "10.0.0.2", "10.0.0.3"), "10.0.0.9")
        assertEquals(SnmpSlotChoice.AllTaken, choice)
    }

    /** Verbatim `oemhp_ping`, which prints status=0 in both outcomes. */
    @Test
    fun `a failed ping is not read as a success just because the command succeeded`() {
        val success = """
            oemhp_ping 203.0.113.13
            32 bytes from 203.0.113.13: icmp_seq=1 errs=0 time=272 ms

            status=0
            status_tag=COMMAND COMPLETED

            The ping was successful.
        """.trimIndent()
        val failure = """
            oemhp_ping 10.0.0.2

            status=0
            status_tag=COMMAND COMPLETED

            The ping failed.
        """.trimIndent()

        assertTrue(parsePingSucceeded(success))
        assertFalse("status=0 est présent dans les deux cas", parsePingSucceeded(failure))
    }

    @Test
    fun `the WireGuard address is the one inside the tunnel, without its prefix`() {
        val network = NetworkConfig(
            name = "VPN",
            type = NetworkType.WIREGUARD,
            wireGuardConfig = """
                [Interface]
                PrivateKey = aaa
                Address = 10.7.0.3/32

                [Peer]
                PublicKey = bbb
                AllowedIPs = 192.168.1.0/24
                Endpoint = vpn.example.org:51820
            """.trimIndent(),
        )

        val destination = trapDestinationFor(network) { listOf("203.0.113.13") }

        // The Wi-Fi address means nothing on the far side of the tunnel.
        assertEquals("10.7.0.3", destination.getOrNull())
    }

    @Test
    fun `without a network the phone's own address is used`() {
        assertEquals(
            "203.0.113.13",
            trapDestinationFor(null) { listOf("203.0.113.13") }.getOrNull(),
        )
    }

    @Test
    fun `a secondary address is used without its prefix`() {
        val network = NetworkConfig(
            name = "Adresse",
            type = NetworkType.SECONDARY_IP,
            interfaceName = "wlan0",
            secondaryAddress = "192.168.1.9/24",
        )
        assertEquals("192.168.1.9", trapDestinationFor(network) { emptyList() }.getOrNull())
    }

    @Test
    fun `an SSH jump host is refused, since traps are UDP`() {
        val network = NetworkConfig(
            name = "Rebond",
            type = NetworkType.SSH_TUNNEL,
            sshTunnelConfig = "{}",
        )
        assertTrue(trapDestinationFor(network) { listOf("203.0.113.13") }.isFailure)
        assertTrue(trapDestinationBlocker(network)!!.contains("UDP"))
    }

    @Test
    fun `a phone with no address is reported rather than sending a blank destination`() {
        val result = trapDestinationFor(null) { emptyList() }
        assertTrue(result.isFailure)
    }
}
