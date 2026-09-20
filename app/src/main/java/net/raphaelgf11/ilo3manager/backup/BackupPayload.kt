package net.raphaelgf11.ilo3manager.backup

import net.raphaelgf11.ilo3manager.data.AuthMethod
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.data.VpnType
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app configuration in a form that can be written out and read back.
 *
 * Kept separate from [net.raphaelgf11.ilo3manager.data.HostRepository]'s own serialisation on
 * purpose: that one is storage for the current version and may change freely, whereas this is a
 * format other installs have to read, so it carries an explicit version and tolerates fields it
 * does not know.
 */
object BackupPayload {

    private const val VERSION = 1

    fun serialize(hosts: List<SshHost>): ByteArray = JSONObject().apply {
        put("version", VERSION)
        put("hosts", JSONArray().apply { hosts.forEach { put(hostToJson(it)) } })
    }.toString().toByteArray(Charsets.UTF_8)

    fun deserialize(bytes: ByteArray): List<SshHost> {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val array = root.optJSONArray("hosts") ?: return emptyList()
        return (0 until array.length()).map { hostFromJson(array.getJSONObject(it)) }
    }

    private fun hostToJson(host: SshHost) = JSONObject().apply {
        put("id", host.id)
        put("name", host.name)
        put("hostname", host.hostname)
        put("port", host.port)
        put("httpsPort", host.httpsPort)
        put("username", host.username)
        put("authMethod", host.authMethod.name)
        put("password", host.password)
        put("privateKey", host.privateKey)
        put("privateKeyPassphrase", host.privateKeyPassphrase)
        put("publicKey", host.publicKey)
        put("ipmiEnabled", host.ipmiEnabled)
        put("ipmiPort", host.ipmiPort)
        put("ipmiPromptDismissed", host.ipmiPromptDismissed)
        put("showStateInList", host.showStateInList)
        put("hardwareOverIpmi", host.hardwareOverIpmi)
        put("vpnType", host.vpnType.name)
        put("wireGuardConfig", host.wireGuardConfig)
        put("sshTunnelConfig", host.sshTunnelConfig)
    }

    private fun hostFromJson(o: JSONObject) = SshHost(
        id = o.getString("id"),
        name = o.optString("name", ""),
        hostname = o.optString("hostname", ""),
        port = o.optInt("port", 22),
        httpsPort = o.optInt("httpsPort", 443),
        username = o.optString("username", ""),
        authMethod = runCatching { AuthMethod.valueOf(o.optString("authMethod")) }
            .getOrDefault(AuthMethod.PASSWORD),
        password = o.optString("password", ""),
        privateKey = o.optString("privateKey", ""),
        privateKeyPassphrase = o.optString("privateKeyPassphrase", ""),
        publicKey = o.optString("publicKey", ""),
        ipmiEnabled = o.optBoolean("ipmiEnabled", false),
        ipmiPort = o.optInt("ipmiPort", 623),
        ipmiPromptDismissed = o.optBoolean("ipmiPromptDismissed", false),
        showStateInList = o.optBoolean("showStateInList", false),
        hardwareOverIpmi = o.optBoolean("hardwareOverIpmi", false),
        vpnType = runCatching { VpnType.valueOf(o.optString("vpnType")) }.getOrDefault(VpnType.NONE),
        wireGuardConfig = o.optString("wireGuardConfig", ""),
        sshTunnelConfig = o.optString("sshTunnelConfig", ""),
    )
}
