package net.raphaelgf11.ilo3manager.webgateway

import fi.iki.elonen.NanoHTTPD
import net.raphaelgf11.ilo3manager.data.SshHost
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory

/**
 * A local HTTP server that transparently proxies each request to the iLO's HTTPS web UI over
 * [LegacyTlsHttpClient], so that Android's WebView (or any modern browser, which refuses
 * iLO3's TLS 1.0/1.1 + legacy ciphers directly) can render it via plain local HTTP.
 *
 * Binds to `127.0.0.1` only by default; when [exposeAllInterfaces] is set, it binds to every
 * interface (IPv4 and IPv6) instead, so another device on the same network can reach it. This
 * removes the "never leaves the device" guarantee, so the UI must make that trade-off explicit.
 *
 * A fresh legacy-TLS connection is opened per request and closed immediately after (`Connection:
 * close`) — simple and robust for the low, interactive traffic of a config UI, at the cost of a
 * TLS handshake per page resource.
 */
class IloHttpProxyServer(
    private val host: SshHost,
    port: Int,
    exposeAllInterfaces: Boolean = false,
) : NanoHTTPD(if (exposeAllInterfaces) null else "127.0.0.1", port) {

    /** Switches this (not-yet-started) server to HTTPS using a locally generated, self-signed certificate. */
    fun enableHttps(keyStore: KeyStore, keyPassword: CharArray) {
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, keyPassword)
        makeSecure(makeSSLSocketFactory(keyStore, keyManagerFactory.keyManagers), null)
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            proxy(session)
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "Passerelle iLO indisponible : ${e.message}",
            )
        }
    }

    private fun proxy(session: IHTTPSession): Response {
        LegacyTlsHttpClient.connect(host.hostname, host.httpsPort).use { connection ->
            val out = connection.outputStream
            val query = session.queryParameterString
            val target = session.uri + if (query.isNullOrEmpty()) "" else "?$query"

            out.write("${session.method.name} $target HTTP/1.1\r\n".toByteArray(Charsets.ISO_8859_1))

            val headers = LinkedHashMap(session.headers)
            headers["host"] = "${host.hostname}:${host.httpsPort}"
            headers["connection"] = "close"
            // Legacy servers often choke on modern encodings/keep-alive hints; keep it simple.
            headers.remove("accept-encoding")
            headers.forEach { (key, value) -> out.write("$key: $value\r\n".toByteArray(Charsets.ISO_8859_1)) }
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength > 0) {
                val body = ByteArray(contentLength)
                var offset = 0
                val input = session.inputStream
                while (offset < contentLength) {
                    val read = input.read(body, offset, contentLength - offset)
                    if (read == -1) break
                    offset += read
                }
                out.write(body, 0, offset)
            }
            out.flush()

            val parsed = RawHttpResponseReader.read(connection.inputStream)
            val status = Response.Status.values().firstOrNull { it.requestStatus == parsed.statusCode }
                ?: Response.Status.OK
            val contentType = parsed.headers["content-type"] ?: "application/octet-stream"
            val response = newFixedLengthResponse(
                status,
                contentType,
                java.io.ByteArrayInputStream(parsed.body),
                parsed.body.size.toLong(),
            )
            parsed.headers.forEach { (key, value) ->
                if (key !in setOf("content-length", "transfer-encoding", "connection", "content-type")) {
                    response.addHeader(key, value)
                }
            }
            return response
        }
    }
}
