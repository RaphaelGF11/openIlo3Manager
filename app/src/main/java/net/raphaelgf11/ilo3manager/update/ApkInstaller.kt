package net.raphaelgf11.ilo3manager.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and hands it to the system installer.
 *
 * The app cannot install anything itself: it can only present the package to Android, which then
 * asks the user to confirm. From API 26 that also requires the "install unknown apps" permission,
 * granted per application in the system settings.
 */
object ApkInstaller {

    private const val FILE_NAME = "update.apk"

    /** True when Android will let this app offer a package for installation. */
    fun canRequestInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the permission above is granted. */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Downloads to the app's own cache. Blocking; call off the main thread.
     * [onProgress] receives a 0..1 fraction, or -1 when the server gives no length.
     */
    fun download(context: Context, url: String, onProgress: (Float) -> Unit): File {
        val target = File(context.cacheDir, FILE_NAME)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 60_000
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Téléchargement refusé (${connection.responseCode})")
            }
            val total = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(if (total > 0) written.toFloat() / total else -1f)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        return target
    }

    /**
     * Presents the downloaded package to the system installer.
     *
     * A FileProvider URI is required: since Android 7 a `file://` URI handed to another app throws.
     */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
