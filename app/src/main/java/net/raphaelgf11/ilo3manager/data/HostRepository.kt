package net.raphaelgf11.ilo3manager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
        put("ipmiPromptDismissed", host.ipmiPromptDismissed)
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
        ipmiPromptDismissed = o.optBoolean("ipmiPromptDismissed", false),
    )

    companion object {
        private const val KEY_HOSTS = "hosts_json"
    }
}
