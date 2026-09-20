package net.raphaelgf11.ilo3manager.vpn

import org.json.JSONObject

/**
 * An SSH jump host used as a tunnel: connections to the iLO are forwarded through it rather than
 * made directly. Stored as JSON in the host's `vpnConfig`, so each tunnel type keeps its own
 * natural format (wg-quick text for WireGuard, structured fields here).
 */
data class SshTunnelConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
) {
    val isUsable: Boolean
        get() = host.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || privateKey.isNotBlank())

    fun toJson(): String = JSONObject().apply {
        put("host", host)
        put("port", port)
        put("username", username)
        put("password", password)
        put("privateKey", privateKey)
        put("passphrase", passphrase)
    }.toString()

    companion object {
        fun fromJson(raw: String): SshTunnelConfig {
            if (raw.isBlank()) return SshTunnelConfig()
            return runCatching {
                val o = JSONObject(raw)
                SshTunnelConfig(
                    host = o.optString("host", ""),
                    port = o.optInt("port", 22),
                    username = o.optString("username", ""),
                    password = o.optString("password", ""),
                    privateKey = o.optString("privateKey", ""),
                    passphrase = o.optString("passphrase", ""),
                )
            }.getOrDefault(SshTunnelConfig())
        }
    }
}
