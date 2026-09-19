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
    /**
     * IPMI/DCMI over LAN is far faster than the SSH CLI for power state/actions, but iLO3 does
     * not enable it by default, so this is opt-in per host and never assumed available.
     */
    val ipmiEnabled: Boolean = false,
    val ipmiPort: Int = 623,
    /** Set once the user has declined the offer to enable IPMI, so it isn't proposed again. */
    val ipmiPromptDismissed: Boolean = false,
)
