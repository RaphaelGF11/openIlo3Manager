package net.raphaelgf11.ilo3manager.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WireGuardConfigParserTest {

    // Same shape as a config exported by a real WireGuard peer; keys are placeholders.
    private val sample = """
        [Interface]
        PrivateKey = qJabmEXzrhP4RczgIDL+KA+1XpF7gzU9KuwGuqTn73w=
        Address = 192.168.27.71/32
        DNS = 212.27.38.253
        MTU = 1360

        [Peer]
        PublicKey = Evosj/3KXJGuuGW59L1K7zpLrSpsAUlNa/rlWNFop1A=
        Endpoint = 82.65.67.31:15268
        AllowedIPs = 0.0.0.0/0, 192.168.27.64/27, 192.168.1.0/24
        PresharedKey = F9gJckeCSDvviUDi7P74kwbvkeAHbO5LrH4+5M8uu6g=
    """.trimIndent()

    @Test
    fun parsesAStandardConfig() {
        val config = WireGuardConfigParser.parse(sample)
        assertEquals(listOf("192.168.27.71/32"), config.addresses)
        assertEquals(listOf("212.27.38.253"), config.dnsServers)
        assertEquals(1360, config.mtu)
        assertEquals("82.65.67.31:15268", config.endpoint)
        assertEquals("82.65.67.31", config.endpointHost)
        assertEquals(
            listOf("0.0.0.0/0", "192.168.27.64/27", "192.168.1.0/24"),
            config.allowedIps,
        )
        assertNull(config.persistentKeepaliveSeconds)
    }

    @Test
    fun toleratesCommentsBlankLinesAndKeyCasing() {
        val messy = """
            # exported by wg-quick
            [interface]
              privatekey=qJabmEXzrhP4RczgIDL+KA+1XpF7gzU9KuwGuqTn73w=
              Address   =   10.0.0.2/32

            [PEER]
            PublicKey = Evosj/3KXJGuuGW59L1K7zpLrSpsAUlNa/rlWNFop1A=  ; inline comment
            Endpoint = vpn.example.org:51820
            AllowedIPs = 10.0.0.0/24
            PersistentKeepalive = 25
        """.trimIndent()
        val config = WireGuardConfigParser.parse(messy)
        assertEquals(listOf("10.0.0.2/32"), config.addresses)
        assertEquals("vpn.example.org:51820", config.endpoint)
        assertEquals(25, config.persistentKeepaliveSeconds)
        assertNull(config.presharedKey)
    }

    @Test
    fun reportsWhichRequiredFieldIsMissing() {
        val noEndpoint = sample.lines().filterNot { it.startsWith("Endpoint") }.joinToString("\n")
        val error = assertThrows(WireGuardConfigException::class.java) {
            WireGuardConfigParser.parse(noEndpoint)
        }
        assertEquals(true, error.message!!.contains("Endpoint"))
    }

    @Test
    fun ignoresAdditionalPeers() {
        val twoPeers = sample + """

            [Peer]
            PublicKey = SECONDPEERKEYSECONDPEERKEYSECONDPEERKEY123=
            Endpoint = 10.9.9.9:51820
            AllowedIPs = 172.16.0.0/12
        """.trimIndent()
        val config = WireGuardConfigParser.parse(twoPeers)
        assertEquals("82.65.67.31:15268", config.endpoint)
    }
}
