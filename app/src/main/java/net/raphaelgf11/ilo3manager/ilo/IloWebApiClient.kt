package net.raphaelgf11.ilo3manager.ilo

import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager
import net.raphaelgf11.ilo3manager.webgateway.LegacyTlsHttpClient
import net.raphaelgf11.ilo3manager.webgateway.RawHttpResponseReader
import org.json.JSONObject
import java.io.IOException

/**
 * Talks to iLO's private JSON API over the same legacy-TLS transport the web gateway uses.
 *
 * This exists because iLO3's SSH CLI cannot toggle IPMI/DCMI over LAN: every other Access Settings
 * option is exposed as a property of /map1/config1 (SSH, HTTP, HTTPS ports...), but no IPMI
 * property exists there — verified against a real unit, where every plausible property name is
 * rejected with COMMAND ERROR-UNSPECIFIED. The web UI is the only interface that can change it, so
 * the app drives that same endpoint directly.
 */
class IloWebApiClient(private val host: SshHost) {

    /** Runs [block] with a logged-in session, always logging out afterwards. */
    private fun <T> withSession(password: String, block: (String) -> T): T {
        val loginBody = JSONObject()
            .put("method", "login")
            .put("user_login", host.username)
            .put("password", password)
            .toString()
        val login = request("POST", "/json/login_session", null, loginBody)
        val sessionKey = runCatching { JSONObject(login).getString("session_key") }.getOrNull()
            ?: throw IOException("Connexion à l'interface web de l'iLO refusée (identifiants incorrects ?)")
        return try {
            block(sessionKey)
        } finally {
            runCatching {
                request("POST", "/json/login_session", sessionKey, JSONObject().put("method", "logout").toString())
            }
        }
    }

    /** True when IPMI/DCMI over LAN is currently enabled on the device itself. */
    fun isIpmiEnabledOnDevice(password: String): Boolean = withSession(password) { sessionKey ->
        val raw = request("GET", "/json/access_settings", sessionKey, null)
        JSONObject(raw).optInt("ipmi_lan_status", 0) == 1
    }

    /** Turns IPMI/DCMI over LAN on or off on the device. */
    fun setIpmiEnabled(password: String, enabled: Boolean) = withSession(password) { sessionKey ->
        val body = JSONObject()
            .put("ipmi_lan_status", if (enabled) 1 else 0)
            .put("method", "set_ipmi_services")
            .put("session_key", sessionKey)
            .toString()
        val raw = request("POST", "/json/access_settings", sessionKey, body)
        // A successful call returns an empty body; anything with a "message" field is an error.
        val message = runCatching { JSONObject(raw).optString("message", "") }.getOrDefault("")
        if (message.isNotBlank()) throw IOException("L'iLO a refusé la modification : $message")
    }

    private fun request(method: String, path: String, sessionKey: String?, body: String?): String {
        val endpoint = HostTunnelManager.endpointFor(host, host.httpsPort)
        LegacyTlsHttpClient.connect(endpoint.host, endpoint.port).use { connection ->
            val payload = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val head = StringBuilder()
            // No query string: iLO3 cannot parse a request body when the request line carries one.
            head.append("$method $path HTTP/1.1\r\n")
            head.append("Host: ${host.hostname}:${host.httpsPort}\r\n")
            head.append("Accept: application/json, text/javascript, */*; q=0.01\r\n")
            head.append("X-Requested-With: XMLHttpRequest\r\n")
            if (sessionKey != null) head.append("Cookie: sessionKey=$sessionKey\r\n")
            if (body != null) {
                head.append("Content-Type: application/x-www-form-urlencoded\r\n")
                head.append("Content-Length: ${payload.size}\r\n")
            }
            head.append("Connection: close\r\n\r\n")

            val out = connection.outputStream
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1) + payload)
            out.flush()

            val response = RawHttpResponseReader.read(connection.inputStream)
            val text = String(response.body, Charsets.UTF_8)
            if (response.statusCode !in 200..299) {
                val detail = runCatching { JSONObject(text).optString("details", "") }.getOrDefault("")
                throw IOException(
                    if (detail.isNotBlank()) detail else "L'iLO a répondu ${response.statusCode}",
                )
            }
            return text
        }
    }
}
