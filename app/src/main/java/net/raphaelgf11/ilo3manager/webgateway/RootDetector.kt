package net.raphaelgf11.ilo3manager.webgateway

import java.io.File

/**
 * Best-effort root detection, used only to decide whether to attempt binding a privileged port
 * (<1024) at all. Note that having root *access* (an `su` binary) does not by itself grant this
 * app's own process the `CAP_NET_BIND_SERVICE` capability required to bind such a port — most
 * apps, even on a rooted device, still can't unless something has explicitly granted it — so a
 * bind failure is still reported clearly even when a device is detected as rooted.
 */
object RootDetector {

    private val SU_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/su",
        "/vendor/bin/su",
        "/su/bin/su",
    )

    fun isProbablyRooted(): Boolean {
        if (SU_PATHS.any { File(it).exists() }) return true
        return runCatching {
            val process = ProcessBuilder("which", "su").redirectErrorStream(true).start()
            process.inputStream.bufferedReader().readLine()?.isNotBlank() == true
        }.getOrDefault(false)
    }
}
