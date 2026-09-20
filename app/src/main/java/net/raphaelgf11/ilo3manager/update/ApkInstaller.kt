package net.raphaelgf11.ilo3manager.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
     * Streams the downloaded package into a [PackageInstaller] session and commits it.
     *
     * Not an `ACTION_VIEW` intent on the APK: that MIME type is claimed by archive managers and
     * terminals too, so Android answers with an "open with" chooser in which the actual installer is
     * merely one entry among several. A session addresses the package manager directly, and the
     * system's own confirmation screen is then opened from [InstallResultReceiver].
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        )
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(FILE_NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            // Mutable, because the system fills in the status extras before delivering it.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val callback = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(context, InstallResultReceiver::class.java),
                flags,
            )
            session.commit(callback.intentSender)
        }
    }
}
