package net.raphaelgf11.ilo3manager.update

import android.content.Context
import android.os.Build
import net.raphaelgf11.ilo3manager.BuildConfig
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A release newer than the one installed, with the asset matching this device. */
data class AvailableUpdate(
    val version: String,
    val notes: String,
    val releaseUrl: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
)

/**
 * Looks for a newer release on GitHub.
 *
 * Releases ship one APK per architecture, so the check also has to pick the right asset: offering
 * the wrong one would download tens of megabytes the device cannot install.
 */
object UpdateChecker {

    private const val LATEST_RELEASE =
        "https://api.github.com/repos/RaphaelGF11/openIlo3Manager/releases/latest"

    /** Blocking; call off the main thread. Returns null when the installed version is current. */
    fun check(context: Context): AvailableUpdate? {
        val json = JSONObject(fetch(LATEST_RELEASE))
        val tag = json.optString("tag_name").ifBlank { return null }
        if (!AppVersion.isNewer(tag, BuildConfig.VERSION_NAME)) return null

        val asset = assetForThisDevice(json) ?: throw IOException(
            "La version $tag ne fournit pas d'APK pour l'architecture de cet appareil " +
                "(${Build.SUPPORTED_ABIS.firstOrNull() ?: "inconnue"}).",
        )
        return AvailableUpdate(
            version = tag.removePrefix("v"),
            notes = json.optString("body", ""),
            releaseUrl = json.optString("html_url", ""),
            apkUrl = asset.optString("browser_download_url"),
            apkSizeBytes = asset.optLong("size", 0L),
        )
    }

    /**
     * The APK built for this device's ABI. Falls back to a lone universal asset, so a release that
     * stops splitting by architecture keeps working.
     */
    private fun assetForThisDevice(release: JSONObject): JSONObject? {
        val assets = release.optJSONArray("assets") ?: return null
        val apks = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".apk") }
        if (apks.isEmpty()) return null

        for (abi in Build.SUPPORTED_ABIS) {
            apks.firstOrNull { it.optString("name").contains(abi) }?.let { return it }
        }
        return apks.singleOrNull()
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("GitHub a répondu ${connection.responseCode}")
            }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }
}
