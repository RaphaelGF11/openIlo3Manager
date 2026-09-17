package net.raphaelgf11.ilo3manager.ilo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ssh.ConnectionState
import net.raphaelgf11.ilo3manager.ssh.IloCliClient

enum class PowerAction(val cliArgument: String) {
    ON("on"),
    OFF("off"),
    FORCE_OFF("off hard"),
    RESET("reset"),
}

/**
 * A quick health scan only queries fans and power supplies (the components most likely to
 * indicate a real problem) so the dashboard tab stays fast on connect. The Matériel tab does a
 * full scan of every component on demand instead.
 */
private val QUICK_SCAN_CATEGORIES = setOf("fan", "powersupply")

/**
 * Targets skipped entirely during the hardware scan: "oemhp_vsp1" shares its name with the
 * interactive `vsp` CLI command and was observed to wedge the whole control session (every
 * subsequent command hangs) rather than answering `show` normally; "log1" is an event log, not
 * hardware status, and can be slow to return.
 */
private val SKIPPED_HARDWARE_CATEGORIES = setOf("oemhp_vsp", "log")

/** Safety net so a single wedged command can never leave the hardware tab spinning forever. */
private const val HARDWARE_SCAN_TIMEOUT_MS = 90_000L

/**
 * Owns the single SSH connection used for iLO CLI commands (power control + health) for one
 * host. Lives in [net.raphaelgf11.ilo3manager.ssh.HostSessionStore], independent of navigation,
 * so it survives leaving and returning to the host list.
 */
class ControlSessionController(private val host: SshHost) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = IloCliClient()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _powerState = MutableStateFlow(PowerState.UNKNOWN)
    val powerState: StateFlow<PowerState> = _powerState

    private val _overallHealth = MutableStateFlow(HealthLevel.UNKNOWN)
    val overallHealth: StateFlow<HealthLevel> = _overallHealth

    private val _powerActionInProgress = MutableStateFlow(false)
    val powerActionInProgress: StateFlow<Boolean> = _powerActionInProgress

    private val _dashboardRefreshing = MutableStateFlow(false)
    val dashboardRefreshing: StateFlow<Boolean> = _dashboardRefreshing

    private val _hardware = MutableStateFlow<List<HardwareComponent>>(emptyList())
    val hardware: StateFlow<List<HardwareComponent>> = _hardware

    private val _hardwareLoading = MutableStateFlow(false)
    val hardwareLoading: StateFlow<Boolean> = _hardwareLoading

    var hardwareLoaded: Boolean = false
        private set

    private val _consoleTranscript = MutableStateFlow("")
    val consoleTranscript: StateFlow<String> = _consoleTranscript

    private val _consoleBusy = MutableStateFlow(false)
    val consoleBusy: StateFlow<Boolean> = _consoleBusy

    fun connectAndLoadDashboard() {
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) return
        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        scope.launch {
            try {
                client.connect(host)
                _connectionState.value = ConnectionState.CONNECTED
                refreshDashboard()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Connexion impossible"
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    fun refreshDashboard() {
        if (_dashboardRefreshing.value) return
        _dashboardRefreshing.value = true
        scope.launch {
            try {
                _powerState.value = IloCliParser.parsePowerState(client.runCommand("power"))
                val components = QUICK_SCAN_CATEGORIES.flatMap { category -> fetchCategory(category) }
                _overallHealth.value = IloCliParser.overallHealth(components.map { it.health })
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Échec de rafraîchissement"
            } finally {
                _dashboardRefreshing.value = false
            }
        }
    }

    fun performPowerAction(action: PowerAction) {
        if (_powerActionInProgress.value) return
        _powerActionInProgress.value = true
        scope.launch {
            try {
                client.runCommand("power ${action.cliArgument}")
                // The BMC needs a moment before it reports the new state accurately.
                kotlinx.coroutines.delay(3_000)
                _powerState.value = IloCliParser.parsePowerState(client.runCommand("power"))
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Échec de la commande d'alimentation"
            } finally {
                _powerActionInProgress.value = false
            }
        }
    }

    fun loadHardwareIfNeeded(force: Boolean = false) {
        if (_hardwareLoading.value) return
        if (hardwareLoaded && !force) return
        _hardwareLoading.value = true
        scope.launch {
            try {
                withTimeout(HARDWARE_SCAN_TIMEOUT_MS) {
                    val rootTargets = IloCliParser.parseTargets(client.runCommand("show /system1"))
                    val components = mutableListOf<HardwareComponent>()
                    for (target in rootTargets) {
                        if (IloCliParser.categoryOf(target) in SKIPPED_HARDWARE_CATEGORIES) continue
                        try {
                            if (IloCliParser.categoryOf(target) == "drives") {
                                fetchDriveBays(target, components)
                            } else {
                                // One level of recursion is enough to pick up e.g. individual
                                // disks nested under a drive bay, and costs nothing extra for
                                // components (sensors, fans, ...) that have no sub-targets.
                                fetchComponentTree(target, "/system1", depth = 0, maxDepth = 1, sink = components)
                            }
                        } catch (_: Exception) {
                            components += HardwareComponent(
                                path = "/system1/$target",
                                category = IloCliParser.categoryOf(target),
                                label = target,
                                health = HealthLevel.UNKNOWN,
                                properties = emptyMap(),
                            )
                        }
                    }
                    _hardware.value = components
                    hardwareLoaded = true
                }
            } catch (e: TimeoutCancellationException) {
                _errorMessage.value = "Le chargement du matériel a dépassé le délai imparti (une commande a bloqué la session). Réessayez."
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Échec du chargement du matériel"
            } finally {
                _hardwareLoading.value = false
            }
        }
    }

    private suspend fun fetchCategory(category: String): List<HardwareComponent> {
        val rootTargets = IloCliParser.parseTargets(client.runCommand("show /system1"))
        val matching = rootTargets.filter { IloCliParser.categoryOf(it) == category }
        return matching.mapNotNull { target ->
            try {
                fetchComponent(target)
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun fetchComponent(target: String): HardwareComponent {
        val path = "/system1/$target"
        val raw = client.runCommand("show $path")
        val properties = IloCliParser.parseProperties(raw)
        return HardwareComponent(
            path = path,
            category = IloCliParser.categoryOf(target),
            label = IloCliParser.labelOf(target, properties),
            health = IloCliParser.healthFromProperties(properties),
            properties = properties,
        )
    }

    private suspend fun fetchComponentTree(
        target: String,
        parentPath: String,
        depth: Int,
        maxDepth: Int,
        sink: MutableList<HardwareComponent>,
    ) {
        val path = "$parentPath/$target"
        val raw = client.runCommand("show $path")
        val properties = IloCliParser.parseProperties(raw)
        sink += HardwareComponent(
            path = path,
            category = IloCliParser.categoryOf(target),
            label = IloCliParser.labelOf(target, properties),
            health = IloCliParser.healthFromProperties(properties),
            properties = properties,
        )
        if (depth < maxDepth) {
            for (child in IloCliParser.parseTargets(raw)) {
                try {
                    fetchComponentTree(child, path, depth + 1, maxDepth, sink)
                } catch (_: Exception) {
                    // Skip a child that fails to answer; the parent component is already recorded.
                }
            }
        }
    }

    /**
     * `show /system1/drivesN` doesn't return simple "key=value" properties but grouped
     * free-text lines (one per drive bay), so it needs its own parser instead of the generic
     * [fetchComponent]. Falls back to a single generic component if the format doesn't match
     * (e.g. a different array controller reports its bays differently).
     */
    private suspend fun fetchDriveBays(target: String, sink: MutableList<HardwareComponent>) {
        val path = "/system1/$target"
        val raw = client.runCommand("show $path")
        val bays = IloCliParser.parseDriveBays(raw)
        if (bays.isEmpty()) {
            sink += HardwareComponent(
                path = path,
                category = "drives",
                label = target,
                health = IloCliParser.healthFromProperties(IloCliParser.parseProperties(raw)),
                properties = IloCliParser.parseProperties(raw),
            )
            return
        }
        for (bay in bays) {
            sink += HardwareComponent(
                path = "$path/bay${bay.bay}",
                category = "drives",
                label = "Baie ${bay.bay} (groupe ${bay.group})",
                health = IloCliParser.healthFromValue(bay.status),
                properties = linkedMapOf(
                    "HealthState" to bay.status,
                    "Firmware" to bay.firmwareVersion,
                    "UID" to bay.uid,
                ),
            )
        }
    }

    fun runConsoleCommand(command: String) {
        if (_consoleBusy.value || command.isBlank()) return
        _consoleBusy.value = true
        _consoleTranscript.value += "> $command\n"
        scope.launch {
            try {
                val raw = client.runCommand(command)
                _consoleTranscript.value += raw.trim() + "\n\n"
            } catch (e: Exception) {
                _consoleTranscript.value += "[erreur] ${e.message}\n\n"
            } finally {
                _consoleBusy.value = false
            }
        }
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun disconnect() {
        client.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        hardwareLoaded = false
        _hardware.value = emptyList()
    }
}
