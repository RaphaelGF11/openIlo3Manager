package net.raphaelgf11.ilo3manager.data

import java.util.UUID

enum class AuthMethod {
    PASSWORD,
    PRIVATE_KEY,
}

data class SshHost(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val httpsPort: Int = 443,
    val username: String,
    val authMethod: AuthMethod,
    val password: String = "",
    val privateKey: String = "",
    val privateKeyPassphrase: String = "",
    val publicKey: String = "",
)
