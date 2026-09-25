package net.raphaelgf11.ilo3manager.backup

import net.raphaelgf11.ilo3manager.data.AuthMethod
import net.raphaelgf11.ilo3manager.data.HostTab
import net.raphaelgf11.ilo3manager.data.NetworkConfig
import net.raphaelgf11.ilo3manager.data.NetworkType
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.data.VpnType
import net.raphaelgf11.ilo3manager.ipmi.IpmiPrivilege
import org.json.JSONArray
import org.json.JSONObject

/** Everything a backup carries. */
data class BackupContents(
    val hosts: List<SshHost>,
    val networks: List<NetworkConfig> = emptyList(),
)

/**
 * The app configuration in a form that can be written out and read back.
 *
 * Kept separate from [net.raphaelgf11.ilo3manager.data.HostRepository]'s own serialisation on
 * purpose: that one is storage for the current version and may change freely, whereas this is a
 * format other installs have to read, so it carries an explicit version and tolerates fields it
 * does not know.
 */
object BackupPayload {

    /** 1 kept the tunnel inside each host; 2 stores networks separately and links hosts to them. */
    private const val VERSION = 2

    fun serialize(contents: BackupContents): ByteArray = JSONObject().apply {
        put("version", VERSION)
        put("hosts", JSONArray().apply {
            contents.hosts.forEach { put(hostToJson(it, contents.networks)) }
        })
        put("networks", JSONArray().apply {
            contents.networks.forEach { put(networkToJson(it)) }
        })
    }.toString().toByteArray(Charsets.UTF_8)

    fun deserialize(bytes: ByteArray): BackupContents {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val hostArray = root.optJSONArray("hosts") ?: JSONArray()
        val networkArray = root.optJSONArray("networks") ?: JSONArray()
        return BackupContents(
            hosts = (0 until hostArray.length()).map { hostFromJson(hostArray.getJSONObject(it)) },
            // Absent in version 1. Those hosts carry their tunnel in the legacy fields instead, and
            // the restoring install folds them into networks exactly as an upgrade does.
            networks = (0 until networkArray.length()).map { networkFromJson(networkArray.getJSONObject(it)) },
        )
    }

    private fun networkToJson(network: NetworkConfig) = JSONObject().apply {
        put("id", network.id)
        put("name", network.name)
        put("type", network.type.name)
        put("wireGuardConfig", network.wireGuardConfig)
        put("sshTunnelConfig", network.sshTunnelConfig)
        put("interfaceName", network.interfaceName)
        put("secondaryAddress", network.secondaryAddress)
    }

    private fun networkFromJson(o: JSONObject) = NetworkConfig(
        id = o.getString("id"),
        name = o.optString("name", ""),
        type = runCatching { NetworkType.valueOf(o.getString("type")) }
            .getOrDefault(NetworkType.WIREGUARD),
        wireGuardConfig = o.optString("wireGuardConfig", ""),
        sshTunnelConfig = o.optString("sshTunnelConfig", ""),
        interfaceName = o.optString("interfaceName", ""),
        secondaryAddress = o.optString("secondaryAddress", ""),
    )

    @Suppress("DEPRECATION")
    private fun hostToJson(host: SshHost, networks: List<NetworkConfig>) = JSONObject().apply {
        put("id", host.id)
        put("name", host.name)
        put("hostname", host.hostname)
        put("port", host.port)
        put("httpsPort", host.httpsPort)
        put("alwaysOpenVsp", host.alwaysOpenVsp)
        put("defaultTab", host.defaultTab.name)
        put("webGatewayOnly", host.webGatewayOnly)
        put("username", host.username)
        put("authMethod", host.authMethod.name)
        put("password", host.password)
        put("privateKey", host.privateKey)
        put("privateKeyPassphrase", host.privateKeyPassphrase)
        put("publicKey", host.publicKey)
        put("ipmiEnabled", host.ipmiEnabled)
        put("ipmiPort", host.ipmiPort)
        put("ipmiPrivilege", host.ipmiPrivilege.name)
        put("ipmiPromptDismissed", host.ipmiPromptDismissed)
        put("showStateInList", host.showStateInList)
        put("hardwareOverIpmi", host.hardwareOverIpmi)
        put("alwaysOpenSsh", host.alwaysOpenSsh)
        put("networkId", host.networkId)

        // The version 1 fields are written too, so a backup made here can still be read by an
        // install that predates networks. Derived from the network at this moment rather than
        // copied from the host record, which would hold whatever was true before the network was
        // last edited. A secondary address has no version 1 equivalent and is simply absent, which
        // an old install reads as a direct connection.
        val network = networks.firstOrNull { it.id == host.networkId }
        put(
            "vpnType",
            when (network?.type) {
                NetworkType.WIREGUARD -> VpnType.WIREGUARD
                NetworkType.SSH_TUNNEL -> VpnType.SSH_TUNNEL
                NetworkType.SECONDARY_IP -> VpnType.NONE
                // Not on a network: a host that predates the migration still carries its own.
                null -> if (host.networkId.isBlank()) host.vpnType else VpnType.NONE
            }.name,
        )
        put("wireGuardConfig", network?.wireGuardConfig ?: legacyOwn(host.wireGuardConfig, host))
        put("sshTunnelConfig", network?.sshTunnelConfig ?: legacyOwn(host.sshTunnelConfig, host))
    }

    /** A host's own legacy configuration, but only while it belongs to no network. */
    private fun legacyOwn(value: String, host: SshHost) =
        if (host.networkId.isBlank()) value else ""

    @Suppress("DEPRECATION")
    private fun hostFromJson(o: JSONObject) = SshHost(
        id = o.getString("id"),
        name = o.optString("name", ""),
        hostname = o.optString("hostname", ""),
        port = o.optInt("port", 22),
        httpsPort = o.optInt("httpsPort", 443),
        alwaysOpenVsp = o.optBoolean("alwaysOpenVsp", false),
        defaultTab = runCatching { HostTab.valueOf(o.optString("defaultTab")) }.getOrDefault(HostTab.POWER),
        webGatewayOnly = o.optBoolean("webGatewayOnly", false),
        username = o.optString("username", ""),
        authMethod = runCatching { AuthMethod.valueOf(o.optString("authMethod")) }
            .getOrDefault(AuthMethod.PASSWORD),
        password = o.optString("password", ""),
        privateKey = o.optString("privateKey", ""),
        privateKeyPassphrase = o.optString("privateKeyPassphrase", ""),
        publicKey = o.optString("publicKey", ""),
        ipmiEnabled = o.optBoolean("ipmiEnabled", false),
        ipmiPort = o.optInt("ipmiPort", 623),
        ipmiPrivilege = runCatching { IpmiPrivilege.valueOf(o.optString("ipmiPrivilege")) }
            .getOrDefault(IpmiPrivilege.OPERATOR),
        ipmiPromptDismissed = o.optBoolean("ipmiPromptDismissed", false),
        showStateInList = o.optBoolean("showStateInList", false),
        hardwareOverIpmi = o.optBoolean("hardwareOverIpmi", false),
        alwaysOpenSsh = o.optBoolean("alwaysOpenSsh", false),
        networkId = o.optString("networkId", ""),
        vpnType = runCatching { VpnType.valueOf(o.optString("vpnType")) }.getOrDefault(VpnType.NONE),
        wireGuardConfig = o.optString("wireGuardConfig", ""),
        sshTunnelConfig = o.optString("sshTunnelConfig", ""),
    )
}
