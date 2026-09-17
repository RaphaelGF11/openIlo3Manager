package net.raphaelgf11.ilo3manager.webgateway

import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.DefaultTlsClient
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.ServerOnlyTlsAuthentication
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Connects to an old embedded HTTPS server (iLO3's web UI only speaks TLS 1.0/1.1 with legacy
 * cipher suites that Android's system TLS provider has removed) using Bouncy Castle's own,
 * independent TLS implementation instead of the platform's [javax.net.ssl.SSLSocket].
 *
 * The server certificate is intentionally never validated: iLO uses a self-signed certificate
 * with no usable chain, and this client only ever talks to hosts the user already trusts enough
 * to hold SSH credentials for (see [net.raphaelgf11.ilo3manager.data.SshHost]), over a
 * loopback-only local proxy — never directly exposed to a real network.
 */
class LegacyTlsConnection internal constructor(
    private val socket: Socket,
    private val protocol: TlsClientProtocol,
) : Closeable {
    val inputStream: InputStream get() = protocol.inputStream
    val outputStream: OutputStream get() = protocol.outputStream

    override fun close() {
        runCatching { protocol.close() }
        runCatching { socket.close() }
    }
}

object LegacyTlsHttpClient {

    // Confirmed against a real iLO3 unit with nmap's ssl-enum-ciphers: it only speaks TLSv1.0/1.1
    // and offers *exclusively* RC4 and 3DES suites — no AES at all. Both are gone from modern
    // OpenSSL/Conscrypt builds, which is exactly why this needs Bouncy Castle's own, independent
    // implementation (it still supports RC4/3DES on request, unlike the platform provider).
    // The DHE_RSA variant is deliberately excluded: iLO only offers a 1024-bit DH group, which
    // Bouncy Castle's client rejects itself as insufficient_security(71); the plain RSA
    // key-exchange suites below don't negotiate DH at all, sidestepping that entirely.
    private val LEGACY_CIPHER_SUITES = intArrayOf(
        CipherSuite.TLS_RSA_WITH_3DES_EDE_CBC_SHA,
        CipherSuite.TLS_RSA_WITH_RC4_128_SHA,
        CipherSuite.TLS_RSA_WITH_RC4_128_MD5,
    )

    fun connect(host: String, port: Int, connectTimeoutMs: Int = 10_000, readTimeoutMs: Int = 20_000): LegacyTlsConnection {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = readTimeoutMs

        val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
        val crypto = BcTlsCrypto(SecureRandom())
        val client = object : DefaultTlsClient(crypto) {
            override fun getAuthentication(): TlsAuthentication = object : ServerOnlyTlsAuthentication() {
                override fun notifyServerCertificate(serverCertificate: TlsServerCertificate?) {
                    // Accept unconditionally: see class-level documentation.
                }
            }

            override fun getSupportedVersions(): Array<ProtocolVersion> =
                ProtocolVersion.TLSv11.downTo(ProtocolVersion.TLSv10)

            override fun getSupportedCipherSuites(): IntArray = LEGACY_CIPHER_SUITES

            // iLO3 predates RFC 5746 (2010) entirely, so it never sends the "secure
            // renegotiation" indication; Bouncy Castle's default behaviour is to abort the
            // handshake itself when that's missing (RFC 5746 §3.6), which is what was causing a
            // *client-side* handshake_failure(40) even though the server was answering fine.
            override fun notifySecureRenegotiation(secureRenegotiation: Boolean) {
                // Deliberately accept: see above.
            }
        }

        try {
            protocol.connect(client)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }

        return LegacyTlsConnection(socket, protocol)
    }
}
