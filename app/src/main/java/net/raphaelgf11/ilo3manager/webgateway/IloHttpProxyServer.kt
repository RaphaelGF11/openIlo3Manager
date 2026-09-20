package net.raphaelgf11.ilo3manager.webgateway

import fi.iki.elonen.NanoHTTPD
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.vpn.Endpoint
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager
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
 * Two things make this usable in practice, both driven by how slow the device is: upstream
 * connections are pooled and reused via keep-alive (a handshake costs over a second, see
 * [LegacyTlsConnectionPool]), and static assets are cached so they are only ever fetched once
 * (see [StaticResourceCache]).
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

    private val staticCache = StaticResourceCache()

    /** Address to dial for iLO's HTTPS port, translated when the host goes through a tunnel. */
    private val tlsEndpoint: Endpoint
        get() = HostTunnelManager.endpointFor(host, host.httpsPort)

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
        val query = session.queryParameterString

        val cacheKey = StaticResourceCache.keyFor(session.method.name, session.uri)
        if (cacheKey != null) {
            staticCache.get(cacheKey)?.let { return toNanoResponse(it, cacheable = true) }
        }

        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(contentLength)
        var bodyLength = 0
        if (contentLength > 0) {
            val input = session.inputStream
            while (bodyLength < contentLength) {
                val read = input.read(body, bodyLength, contentLength - bodyLength)
                if (read == -1) break
                bodyLength += read
            }
        }

        val requestBytes = buildRequest(session, query, body, bodyLength)

        // A connection taken from the pool may have been closed by the server in the meantime;
        // that failure is indistinguishable from a real one until the write/read fails, so retry
        // once on a guaranteed-fresh connection. A freshly created connection failing is a real
        // error and is not retried.
        val pooled = LegacyTlsConnectionPool.acquire(tlsEndpoint.host, tlsEndpoint.port)
        val parsed = try {
            exchange(pooled.connection, requestBytes)
        } catch (e: Exception) {
            LegacyTlsConnectionPool.discard(pooled.connection)
            if (!pooled.fromPool) throw e
            val fresh = LegacyTlsConnectionPool.acquire(tlsEndpoint.host, tlsEndpoint.port)
            try {
                exchange(fresh.connection, requestBytes)
            } catch (retry: Exception) {
                LegacyTlsConnectionPool.discard(fresh.connection)
                throw retry
            }
        }

        if (cacheKey != null && parsed.statusCode == 200) {
            staticCache.put(cacheKey, parsed)
        }
        return toNanoResponse(parsed, cacheable = cacheKey != null)
    }

    /** Writes [requestBytes], reads the reply, and returns the connection to the pool if it stays usable. */
    private fun exchange(connection: LegacyTlsConnection, requestBytes: ByteArray): RawHttpResponse {
        connection.outputStream.write(requestBytes)
        connection.outputStream.flush()

        val parsed = RawHttpResponseReader.read(connection.inputStream)

        // Only a response whose body length was explicit leaves the stream positioned exactly at
        // the start of the next reply; an EOF-framed one consumed everything and says nothing
        // about where the next response would begin, so that connection can't be reused.
        val explicitlyFramed = parsed.headers.containsKey("content-length") ||
            parsed.headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val serverClosing = parsed.headers["connection"]?.contains("close", ignoreCase = true) == true
        if (explicitlyFramed && !serverClosing) {
            LegacyTlsConnectionPool.release(tlsEndpoint.host, tlsEndpoint.port, connection)
        } else {
            LegacyTlsConnectionPool.discard(connection)
        }

        return parsed
    }

    private fun buildRequest(
        session: IHTTPSession,
        query: String?,
        body: ByteArray,
        bodyLength: Int,
    ): ByteArray {
        val iloOrigin = "https://${host.hostname}${if (host.httpsPort == 443) "" else ":${host.httpsPort}"}"
        val headers = LinkedHashMap(session.headers)
        headers["host"] = "${host.hostname}:${host.httpsPort}"
        // Keep-alive is what makes connection pooling possible; iLO3 honours it.
        headers["connection"] = "keep-alive"
        // Legacy servers often choke on modern encodings; keep it simple.
        headers.remove("accept-encoding")
        // These reflect our own gateway's address (the "browser"'s view of who it's talking
        // to), not iLO's — iLO's REST API validates Origin/Referer against its own hostname
        // for state-changing requests and otherwise rejects them (surfacing as a confusing
        // "Malformed object" JSON-parse error rather than a clear CSRF/origin failure).
        if (headers.containsKey("origin")) headers["origin"] = iloOrigin
        headers["referer"]?.let { referer ->
            headers["referer"] = referer.replaceFirst(Regex("^https?://[^/]+"), iloOrigin)
        }
        // NanoHTTPD's own connection-metadata pseudo-headers, not real client headers — never
        // meant to be forwarded to the upstream server.
        headers.remove("remote-addr")
        headers.remove("http-client-ip")
        val headerLines = StringBuilder()
        headers.forEach { (key, value) ->
            headerLines.append("${canonicalHeaderName(key)}: $value\r\n")
        }

        // iLO3's web server cannot parse a request body when the request line carries a query
        // string — *any* query string, even a bare "?", makes it answer
        // "Malformed object, expected '{' at start of object" (verified against the real unit
        // for "?", "?null", "?_=123", "?x=1"). jQuery appends cache-busting parameters to the
        // UI's AJAX calls, so every settings-changing POST hit this. Dropping the query is
        // safe here: iLO's JSON API takes all of its arguments in the body, and by definition
        // no POST that carries a body can be relying on query parameters, since the firmware
        // cannot read the body in that case at all.
        val target = session.uri + when {
            query.isNullOrEmpty() -> ""
            bodyLength > 0 -> ""
            else -> "?$query"
        }
        val requestLine = "${session.method.name} $target HTTP/1.1"

        // Sent as a single write: Bouncy Castle emits one TLS record per write() call, so
        // assembling the whole request first keeps it in as few records as possible.
        // NOTE: never log this buffer — it carries the user's iLO credentials on the login
        // request and their session key on every authenticated one.
        val request = java.io.ByteArrayOutputStream(requestLine.length + headerLines.length + bodyLength + 8)
        request.write("$requestLine\r\n".toByteArray(Charsets.ISO_8859_1))
        request.write(headerLines.toString().toByteArray(Charsets.ISO_8859_1))
        request.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        if (bodyLength > 0) request.write(body, 0, bodyLength)
        return request.toByteArray()
    }

    private fun toNanoResponse(parsed: RawHttpResponse, cacheable: Boolean = false): Response {
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
            if (key !in SKIPPED_RESPONSE_HEADERS) {
                response.addHeader(key, value)
            }
        }
        // iLO sends an ETag and a Last-Modified but no Cache-Control, so browsers revalidate these
        // assets on every page view — each revalidation costing a round trip to a device that
        // serves at ~35 KB/s. Since the content only changes with a firmware update, tell the
        // browser it can reuse them outright.
        if (cacheable && parsed.statusCode == 200) {
            response.addHeader("Cache-Control", "private, max-age=86400")
        }
        return response
    }

    private companion object {
        /** Re-generated by NanoHTTPD for its own connection to the browser; forwarding iLO's would conflict. */
        val SKIPPED_RESPONSE_HEADERS = setOf("content-length", "transfer-encoding", "connection", "content-type")
    }

    /** Title-Cases a lowercased header name the way a real browser would send it (e.g. "content-length" -> "Content-Length"). */
    private fun canonicalHeaderName(name: String): String =
        name.split('-').joinToString("-") { part -> part.replaceFirstChar { it.uppercaseChar() } }
}
