package net.raphaelgf11.ilo3manager.backup

import net.raphaelgf11.ilo3manager.data.AuthMethod
import net.raphaelgf11.ilo3manager.data.NetworkConfig
import net.raphaelgf11.ilo3manager.data.NetworkType
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.data.VpnType
import net.raphaelgf11.ilo3manager.data.foldLegacyVpn
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup is read back on another phone, months later, by an install that may be older or newer
 * than the one that wrote it. Everything that crosses that gap is pinned here — a loss shows up
 * only at restore, when the original is already gone.
 */
class BackupPayloadTest {

    private fun host(
        name: String,
        networkId: String = "",
        vpn: VpnType = VpnType.NONE,
        wireGuard: String = "",
    ) = SshHost(
        name = name,
        hostname = "192.168.1.230",
        username = "user",
        authMethod = AuthMethod.PASSWORD,
        networkId = networkId,
        vpnType = vpn,
        wireGuardConfig = wireGuard,
    )

    @Test
    fun `a network survives the round trip with its name and its link to the host`() {
        val network = NetworkConfig(
            name = "VPN maison",
            type = NetworkType.WIREGUARD,
            wireGuardConfig = "[Interface]\nPrivateKey = abc",
        )
        val original = BackupContents(
            hosts = listOf(host("DL 380", networkId = network.id)),
            networks = listOf(network),
        )

        val restored = BackupPayload.deserialize(BackupPayload.serialize(original))

        assertEquals(1, restored.networks.size)
        assertEquals("VPN maison", restored.networks[0].name)
        assertEquals(network.id, restored.networks[0].id)
        assertEquals("[Interface]\nPrivateKey = abc", restored.networks[0].wireGuardConfig)
        assertEquals(network.id, restored.hosts[0].networkId)
    }

    @Test
    fun `two hosts on one network still share it after a restore`() {
        val network = NetworkConfig(name = "VPN", type = NetworkType.WIREGUARD, wireGuardConfig = "k")
        val restored = BackupPayload.deserialize(
            BackupPayload.serialize(
                BackupContents(
                    hosts = listOf(
                        host("DL 380", networkId = network.id),
                        host("DL 360", networkId = network.id),
                    ),
                    networks = listOf(network),
                ),
            ),
        )

        assertEquals(1, restored.networks.size)
        assertEquals(restored.hosts[0].networkId, restored.hosts[1].networkId)
    }

    @Test
    fun `a secondary address survives, though version 1 has no such thing`() {
        val network = NetworkConfig(
            name = "Adresse directe",
            type = NetworkType.SECONDARY_IP,
            interfaceName = "wlan0",
            secondaryAddress = "192.168.1.9/24",
        )
        val restored = BackupPayload.deserialize(
            BackupPayload.serialize(
                BackupContents(listOf(host("DL 380", networkId = network.id)), listOf(network)),
            ),
        )

        assertEquals(NetworkType.SECONDARY_IP, restored.networks[0].type)
        assertEquals("wlan0", restored.networks[0].interfaceName)
        assertEquals("192.168.1.9/24", restored.networks[0].secondaryAddress)
    }

    @Test
    fun `a version 1 file still restores a working tunnel`() {
        // Written by an install that predates networks: no "networks" array, no "networkId", the
        // tunnel sits in the host. Restoring must not silently drop it.
        val version1 = """
            {"version":1,"hosts":[{
              "id":"h1","name":"DL 380","hostname":"192.168.1.230","port":22,
              "username":"user","authMethod":"PASSWORD",
              "vpnType":"WIREGUARD","wireGuardConfig":"[Interface]\nPrivateKey = abc"
            }]}
        """.trimIndent().toByteArray()

        val restored = BackupPayload.deserialize(version1)

        assertTrue("aucun réseau n'est encore nommé", restored.networks.isEmpty())
        assertEquals(VpnType.WIREGUARD, restored.hosts[0].vpnType)

        // The restoring install folds it, which is what makes the tunnel usable again.
        val folded = foldLegacyVpn(restored.hosts, restored.networks)
        assertEquals(1, folded.networks.size)
        assertEquals("[Interface]\nPrivateKey = abc", folded.networks[0].wireGuardConfig)
    }

    @Test
    fun `a backup written now is still readable by a version 1 install`() {
        // The old fields are derived from the network at write time rather than copied from the
        // host, which would hold whatever was true before the network was last edited.
        val network = NetworkConfig(
            name = "VPN",
            type = NetworkType.WIREGUARD,
            wireGuardConfig = "config à jour",
        )
        val bytes = BackupPayload.serialize(
            BackupContents(
                // The host's own legacy field is deliberately stale here.
                hosts = listOf(host("DL 380", networkId = network.id, vpn = VpnType.WIREGUARD, wireGuard = "vieille config")),
                networks = listOf(network),
            ),
        )

        val asOldInstallSeesIt = JSONObject(String(bytes)).getJSONArray("hosts").getJSONObject(0)
        assertEquals("WIREGUARD", asOldInstallSeesIt.getString("vpnType"))
        assertEquals("config à jour", asOldInstallSeesIt.getString("wireGuardConfig"))
    }

    @Test
    fun `a host on a secondary address reads as direct to a version 1 install`() {
        // There is no version 1 equivalent, and claiming a tunnel it cannot build would be worse
        // than admitting there is none.
        val network = NetworkConfig(
            name = "Adresse",
            type = NetworkType.SECONDARY_IP,
            interfaceName = "wlan0",
            secondaryAddress = "192.168.1.9/24",
        )
        val bytes = BackupPayload.serialize(
            BackupContents(listOf(host("DL 380", networkId = network.id)), listOf(network)),
        )

        val asOldInstallSeesIt = JSONObject(String(bytes)).getJSONArray("hosts").getJSONObject(0)
        assertEquals("NONE", asOldInstallSeesIt.getString("vpnType"))
    }

    @Test
    fun `an untunnelled host keeps its credentials`() {
        val restored = BackupPayload.deserialize(
            BackupPayload.serialize(
                BackupContents(
                    listOf(
                        host("direct").copy(
                            password = "secret",
                            privateKey = "-----BEGIN-----",
                            ipmiEnabled = true,
                            ipmiPort = 624,
                        ),
                    ),
                ),
            ),
        )

        assertEquals("secret", restored.hosts[0].password)
        assertEquals("-----BEGIN-----", restored.hosts[0].privateKey)
        assertTrue(restored.hosts[0].ipmiEnabled)
        assertEquals(624, restored.hosts[0].ipmiPort)
        assertTrue(restored.hosts[0].networkId.isEmpty())
    }
}
