package net.raphaelgf11.ilo3manager.ssh

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import net.raphaelgf11.ilo3manager.data.AuthMethod
import net.raphaelgf11.ilo3manager.data.SshHost

/**
 * iLO3's SSH daemon only speaks legacy algorithms (ssh-dss host key,
 * diffie-hellman-group1-sha1 / group14-sha1 key exchange) that modern SSH
 * clients disable by default, so they are explicitly re-enabled here.
 *
 * iLO3 also only accepts a small number of concurrent SSH sessions (as few
 * as one or two) and can wedge a session if it is sent an invalid target
 * path, so callers must keep a single [Session] alive per purpose and reuse
 * it rather than reconnecting per command.
 */
object IloSessionFactory {

    fun connect(host: SshHost, timeoutMs: Int = 15_000): Session {
        val jsch = JSch()
        if (host.authMethod == AuthMethod.PRIVATE_KEY && host.privateKey.isNotBlank()) {
            jsch.addIdentity(
                host.id,
                host.privateKey.toByteArray(),
                null,
                host.privateKeyPassphrase.takeIf { it.isNotBlank() }?.toByteArray(),
            )
        }

        val session = jsch.getSession(host.username, host.hostname, host.port)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setConfig("kex", session.getConfig("kex") + ",diffie-hellman-group14-sha1,diffie-hellman-group1-sha1")
        session.setConfig("server_host_key", session.getConfig("server_host_key") + ",ssh-dss")
        session.setConfig("PubkeyAcceptedAlgorithms", session.getConfig("PubkeyAcceptedAlgorithms") + ",ssh-dss")

        if (host.authMethod == AuthMethod.PASSWORD) {
            session.setPassword(host.password)
        }

        session.connect(timeoutMs)
        return session
    }
}
