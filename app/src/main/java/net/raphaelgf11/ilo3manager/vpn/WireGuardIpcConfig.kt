package net.raphaelgf11.ilo3manager.vpn

import android.util.Base64

/**
 * Renders a [WireGuardConfig] into wireguard-go's UAPI configuration format.
 *
 * The two formats differ in a way that silently breaks a tunnel if missed: a wg-quick .conf holds
 * keys in base64, while the UAPI protocol expects them in lowercase hex. Endpoints and allowed IPs
 * also become one line each rather than comma-separated lists.
 */
object WireGuardIpcConfig {

    fun render(config: WireGuardConfig): String = buildString {
        appendLine("private_key=${hexKey(config.privateKey)}")
        appendLine("public_key=${hexKey(config.peerPublicKey)}")
        config.presharedKey?.takeIf { it.isNotBlank() }?.let {
            appendLine("preshared_key=${hexKey(it)}")
        }
        appendLine("endpoint=${config.endpoint}")
        config.allowedIps.forEach { appendLine("allowed_ip=$it") }
        config.persistentKeepaliveSeconds?.let {
            appendLine("persistent_keepalive_interval=$it")
        }
    }

    /** base64 (as written in a .conf) to the lowercase hex the UAPI protocol expects. */
    fun hexKey(base64Key: String): String {
        val bytes = Base64.decode(base64Key.trim(), Base64.DEFAULT)
        require(bytes.size == 32) { "Clé WireGuard invalide : ${bytes.size} octets au lieu de 32." }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
