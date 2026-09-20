package net.raphaelgf11.ilo3manager.backup

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads and writes a single file in Drive's application data folder.
 *
 * That folder is private to the application: its contents do not appear in the Drive interface and
 * the user cannot browse or download them — the same arrangement game servers use for their
 * backups. Note this is invisibility, not confidentiality, which is why the payload is encrypted
 * before it gets here (see [BackupCrypto]).
 *
 * The REST endpoints are called directly rather than through Google's API client library: this
 * needs three requests, and that library would add a large dependency tree for them.
 */
class DriveAppDataClient(private val accessToken: String) {

    /** Uploads the backup, replacing the previous one so the folder holds a single current copy. */
    fun upload(content: ByteArray) {
        val existingId = findBackupId()
        if (existingId == null) create(content) else update(existingId, content)
    }

    /** The stored backup, or null when this account has never synchronised. */
    fun download(): ByteArray? {
        val id = findBackupId() ?: return null
        val connection = open("$DRIVE_FILES/$id?alt=media", "GET")
        return connection.readOrThrow()
    }

    fun deleteBackup() {
        val id = findBackupId() ?: return
        open("$DRIVE_FILES/$id", "DELETE").readOrThrow()
    }

    private fun findBackupId(): String? {
        val query = "spaces=appDataFolder&q=name%3D'$FILE_NAME'&fields=files(id)"
        val body = open("$DRIVE_FILES?$query", "GET").readOrThrow()
        val files = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("files")
        if (files == null || files.length() == 0) return null
        return files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
    }

    private fun create(content: ByteArray) {
        val metadata = JSONObject().apply {
            put("name", FILE_NAME)
            put("parents", org.json.JSONArray().put("appDataFolder"))
        }
        multipart("$DRIVE_UPLOAD?uploadType=multipart", "POST", metadata, content)
    }

    private fun update(fileId: String, content: ByteArray) {
        val connection = open("$DRIVE_UPLOAD/$fileId?uploadType=media", "PATCH")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.outputStream.use { it.write(content) }
        connection.readOrThrow()
    }

    private fun multipart(url: String, method: String, metadata: JSONObject, content: ByteArray) {
        val boundary = "ilo3manager-${System.currentTimeMillis()}"
        val connection = open(url, method)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
        connection.outputStream.use { out ->
            out.write(
                ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                    "$metadata\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n")
                    .toByteArray(Charsets.UTF_8),
            )
            out.write(content)
            out.write("\r\n--$boundary--".toByteArray(Charsets.UTF_8))
        }
        connection.readOrThrow()
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $accessToken")
            connectTimeout = 15_000
            readTimeout = 30_000
        }

    private fun HttpURLConnection.readOrThrow(): ByteArray {
        val code = responseCode
        if (code !in 200..299) {
            val body = runCatching { errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
            disconnect()
            throw IOException("Google Drive a répondu $code : ${summarize(body)}")
        }
        return inputStream.use { it.readBytes() }.also { disconnect() }
    }

    /**
     * Google's error bodies nest the same sentence half a dozen times across several hundred
     * lines; dumping that verbatim fills the screen and buries everything else. Only the top-level
     * message is actionable.
     */
    private fun summarize(body: String?): String {
        if (body.isNullOrBlank()) return "aucun détail"
        return runCatching {
            JSONObject(body).getJSONObject("error").getString("message")
        }.getOrElse { body.take(200) }
    }

    private companion object {
        const val DRIVE_FILES = "https://www.googleapis.com/drive/v3/files"
        const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val FILE_NAME = "ilo3manager-config.enc"
    }
}
