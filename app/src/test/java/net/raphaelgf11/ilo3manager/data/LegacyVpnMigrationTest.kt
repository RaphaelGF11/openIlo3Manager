package net.raphaelgf11.ilo3manager.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The migration runs once over configurations users already have, so its failures would be silent
 * and would cost them a working tunnel. Every rule it relies on is pinned here.
 */
class LegacyVpnMigrationTest {

    private fun host(
        name: String,
        vpn: VpnType = VpnType.NONE,
        wireGuard: String = "",
        ssh: String = "",
        networkId: String = "",
    ) = SshHost(
        name = name,
        hostname = "192.168.1.230",
        username = "user",
        authMethod = AuthMethod.PASSWORD,
        networkId = networkId,
        vpnType = vpn,
        wireGuardConfig = wireGuard,
        sshTunnelConfig = ssh,
    )

    @Test
    fun `two hosts sharing a configuration land on one network`() {
        val config = "[Interface]\nPrivateKey = abc"
        val result = foldLegacyVpn(
            listOf(
                host("DL 380", VpnType.WIREGUARD, wireGuard = config),
                host("DL 360", VpnType.WIREGUARD, wireGuard = config),
            ),
            emptyList(),
        )

        assertEquals(1, result.networks.size)
        assertEquals(2, result.hosts.size)
        assertEquals(result.hosts[0].networkId, result.hosts[1].networkId)
        assertEquals(result.networks[0].id, result.hosts[0].networkId)
    }

    @Test
    fun `different configurations stay separate`() {
        val result = foldLegacyVpn(
            listOf(
                host("A", VpnType.WIREGUARD, wireGuard = "[Interface]\nPrivateKey = aaa"),
                host("B", VpnType.WIREGUARD, wireGuard = "[Interface]\nPrivateKey = bbb"),
            ),
            emptyList(),
        )

        assertEquals(2, result.networks.size)
        assertNotEquals(result.hosts[0].networkId, result.hosts[1].networkId)
    }

    @Test
    fun `the same text under two types is not folded together`() {
        val shared = "identique"
        val result = foldLegacyVpn(
            listOf(
                host("A", VpnType.WIREGUARD, wireGuard = shared),
                host("B", VpnType.SSH_TUNNEL, ssh = shared),
            ),
            emptyList(),
        )

        assertEquals(2, result.networks.size)
        assertEquals(NetworkType.WIREGUARD, result.networks[0].type)
        assertEquals(NetworkType.SSH_TUNNEL, result.networks[1].type)
        assertEquals(shared, result.networks[1].sshTunnelConfig)
        assertTrue(result.networks[1].wireGuardConfig.isEmpty())
    }

    @Test
    fun `running it twice changes nothing the second time`() {
        val hosts = listOf(host("DL 380", VpnType.WIREGUARD, wireGuard = "[Interface]"))
        val first = foldLegacyVpn(hosts, emptyList())

        val migrated = hosts.map { original ->
            first.hosts.firstOrNull { it.id == original.id } ?: original
        }
        val second = foldLegacyVpn(migrated, first.networks)

        assertTrue("un second passage ne doit rien modifier", second.hosts.isEmpty())
        assertEquals(first.networks.size, second.networks.size)
    }

    @Test
    fun `a host with no tunnel is left alone`() {
        val result = foldLegacyVpn(listOf(host("direct")), emptyList())
        assertTrue(result.hosts.isEmpty())
        assertTrue(result.networks.isEmpty())
    }

    @Test
    fun `an empty configuration does not become a network`() {
        // Folding on configuration text would otherwise gather every unconfigured host onto one
        // shared, useless network.
        val result = foldLegacyVpn(
            listOf(
                host("A", VpnType.WIREGUARD, wireGuard = ""),
                host("B", VpnType.WIREGUARD, wireGuard = ""),
            ),
            emptyList(),
        )
        assertTrue(result.networks.isEmpty())
        assertTrue(result.hosts.isEmpty())
    }

    @Test
    fun `migrated networks get distinct names`() {
        val result = foldLegacyVpn(
            listOf(
                host("DL 380", VpnType.WIREGUARD, wireGuard = "aaa"),
                host("DL 380", VpnType.WIREGUARD, wireGuard = "bbb"),
            ),
            emptyList(),
        )
        assertEquals(2, result.networks.map { it.name }.distinct().size)
    }
}
