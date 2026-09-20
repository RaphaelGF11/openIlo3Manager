package net.raphaelgf11.ilo3manager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.raphaelgf11.ilo3manager.ipmi.IpmiPrivilege
import org.json.JSONArray
import org.json.JSONObject

class HostRepository(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "ilo3manager_hosts",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getHosts(): List<SshHost> {
        val json = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i -> fromJson(array.getJSONObject(i)) }
    }

    fun saveHost(host: SshHost) {
        val hosts = getHosts().toMutableList()
        val index = hosts.indexOfFirst { it.id == host.id }
        if (index >= 0) hosts[index] = host else hosts.add(host)
        persist(hosts)
    }

    fun deleteHost(id: String) {
        persist(getHosts().filterNot { it.id == id })
    }

    fun reorderHosts(orderedHosts: List<SshHost>) {
        persist(orderedHosts)
    }

    private fun persist(hosts: List<SshHost>) {
        val array = JSONArray()
        hosts.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_HOSTS, array.toString()).apply()
    }

    private fun toJson(host: SshHost) = JSONObject().apply {
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
        put("ipmiPrivilege", host.ipmiPrivilege.name)
        put("ipmiPromptDismissed", host.ipmiPromptDismissed)
        put("showStateInList", host.showStateInList)
        put("hardwareOverIpmi", host.hardwareOverIpmi)
        put("vpnType", host.vpnType.name)
        put("wireGuardConfig", host.wireGuardConfig)
        put("sshTunnelConfig", host.sshTunnelConfig)
    }

    private fun fromJson(o: JSONObject) = SshHost(
        id = o.getString("id"),
        name = o.getString("name"),
        hostname = o.getString("hostname"),
        port = o.getInt("port"),
        httpsPort = o.optInt("httpsPort", 443),
        username = o.getString("username"),
        authMethod = AuthMethod.valueOf(o.getString("authMethod")),
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
        vpnType = runCatching { VpnType.valueOf(o.optString("vpnType", "NONE")) }.getOrDefault(VpnType.NONE),
        // "vpnConfig" was a single shared field before each type got its own; map it onto the
        // matching one so an existing configuration survives the upgrade.
        wireGuardConfig = o.optString("wireGuardConfig", "").ifBlank {
            if (o.optString("vpnType", "") == "WIREGUARD") o.optString("vpnConfig", "") else ""
        },
        sshTunnelConfig = o.optString("sshTunnelConfig", "").ifBlank {
            if (o.optString("vpnType", "") == "SSH_TUNNEL") o.optString("vpnConfig", "") else ""
        },
    )

    companion object {
        private const val KEY_HOSTS = "hosts_json"
    }
}
