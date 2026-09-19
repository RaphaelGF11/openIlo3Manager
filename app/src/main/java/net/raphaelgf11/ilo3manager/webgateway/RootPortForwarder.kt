package net.raphaelgf11.ilo3manager.webgateway

/**
 * Works around the fact that even a rooted device does not grant this app's own process
 * `CAP_NET_BIND_SERVICE` (see [RootDetector]): instead of binding the privileged port directly,
 * the gateway binds an ordinary ephemeral port and root (`su`) installs an iptables NAT REDIRECT
 * rule that forwards the privileged port to it. `OUTPUT`/`-o lo` catches locally-originated
 * connections (e.g. the "open in browser" button hitting 127.0.0.1), `PREROUTING` catches
 * connections arriving from the LAN when the gateway is exposed on all interfaces.
 */
object RootPortForwarder {

    /**
     * If the app process was killed (crash, reinstall, task-kill) instead of going through
     * [WebGatewayManager.stop], the iptables rule from that run is never removed — it lingers
     * forever and iptables always matches the *first* rule for a given port, so it would keep
     * redirecting to a now-dead port no matter what a later run installs. [actualPort] is
     * deterministic (derived from [publicPort], see [WebGatewayManager]) specifically so this
     * best-effort cleanup can target the exact same rule text a previous run would have installed
     * and remove it before adding a fresh one.
     */
    fun redirect(publicPort: Int, actualPort: Int, exposeAllInterfaces: Boolean): Boolean {
        removeRedirect(publicPort, actualPort, exposeAllInterfaces)
        return runAsRoot(commandsFor("-A", publicPort, actualPort, exposeAllInterfaces))
    }

    fun removeRedirect(publicPort: Int, actualPort: Int, exposeAllInterfaces: Boolean) {
        runAsRoot(commandsFor("-D", publicPort, actualPort, exposeAllInterfaces))
    }

    private fun commandsFor(op: String, publicPort: Int, actualPort: Int, exposeAllInterfaces: Boolean): List<String> =
        buildList {
            add("iptables -t nat $op OUTPUT -p tcp -o lo --dport $publicPort -j REDIRECT --to-port $actualPort")
            if (exposeAllInterfaces) {
                add("iptables -t nat $op PREROUTING -p tcp --dport $publicPort -j REDIRECT --to-port $actualPort")
            }
        }

    /**
     * Runs each command as its own `su -c "..."` invocation and checks *that* invocation's exit
     * code directly. Piping several commands into one persistent `su` shell and checking only the
     * shell's own final exit code (e.g. from a trailing `exit`) is a trap: a failed `iptables` in
     * the middle doesn't abort the shell, so the last command's (successful) exit code masks it —
     * which is exactly how this previously reported success while installing no rule at all.
     */
    private fun runAsRoot(commands: List<String>): Boolean = commands.all { command ->
        runCatching {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                android.util.Log.w("RootPortForwarder", "Command failed ($exitCode): $command -> $output")
            }
            exitCode == 0
        }.getOrDefault(false)
    }
}
