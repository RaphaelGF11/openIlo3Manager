package net.raphaelgf11.ilo3manager.webgateway

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** A parsed raw HTTP/1.1 response, read directly off a socket (no framework involved). */
data class RawHttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/**
 * Minimal HTTP/1.1 response parser for reading iLO's raw reply off the legacy-TLS stream: status
 * line, headers, then a body framed by Content-Length, chunked Transfer-Encoding, or (since the
 * proxy always sends `Connection: close`) simply read-until-EOF as a last resort.
 */
object RawHttpResponseReader {

    fun read(input: InputStream): RawHttpResponse {
        val statusLine = readLine(input)
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 502

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
            }
        }

        val transferEncoding = headers["transfer-encoding"]
        val contentLength = headers["content-length"]?.toIntOrNull()
        val body = when {
            transferEncoding?.contains("chunked", ignoreCase = true) == true -> readChunkedBody(input)
            contentLength != null -> readExact(input, contentLength)
            else -> input.readBytes()
        }

        return RawHttpResponse(statusCode, headers, body)
    }

    private fun readLine(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) break
            if (prev == '\r'.code && b == '\n'.code) {
                val bytes = buffer.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.ISO_8859_1)
            }
            buffer.write(b)
            prev = b
        }
        return String(buffer.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == size) buffer else buffer.copyOf(offset)
    }

    private fun readChunkedBody(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input).substringBefore(';').trim()
            val size = sizeLine.toIntOrNull(16) ?: break
            if (size == 0) {
                while (readLine(input).isNotEmpty()) {
                    // Consume trailing headers after the final chunk.
                }
                break
            }
            out.write(readExact(input, size))
            readLine(input) // trailing CRLF after each chunk's data
        }
        return out.toByteArray()
    }
}
