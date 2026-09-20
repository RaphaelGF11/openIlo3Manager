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
import net.raphaelgf11.ilo3manager.ipmi.ChassisControl
import net.raphaelgf11.ilo3manager.ipmi.ChassisPowerState
import net.raphaelgf11.ilo3manager.ipmi.IpmiLanClient
import net.raphaelgf11.ilo3manager.ipmi.IpmiSensorReader
import net.raphaelgf11.ilo3manager.ipmi.SdrEntry
import net.raphaelgf11.ilo3manager.ipmi.SensorHealth
import net.raphaelgf11.ilo3manager.ssh.ConnectionState
import net.raphaelgf11.ilo3manager.ssh.IloCliClient
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager

enum class PowerAction(val cliArgument: String, val ipmiControl: ChassisControl) {
    ON("on", ChassisControl.POWER_UP),
    OFF("off", ChassisControl.SOFT_SHUTDOWN),
    FORCE_OFF("off hard", ChassisControl.POWER_DOWN),
    RESET("reset", ChassisControl.HARD_RESET),
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
class ControlSessionController(private var host: SshHost) {

    /**
     * Applies an edited host record to this already-live session. Needed because the controller is
     * cached per host id in [net.raphaelgf11.ilo3manager.ssh.HostSessionStore]: without this it
     * would keep serving the SshHost captured when it was first created, so a setting changed
     * during the session (enabling IPMI, say) would only take effect after an app restart.
     */
    fun updateHost(updated: SshHost) {
        host = updated
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = IloCliClient()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    /**
     * Readiness of the power dashboard specifically. Tracked separately from [connectionState]
     * because over IPMI the dashboard works with no SSH session at all.
     */
    private val _dashboardState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val dashboardState: StateFlow<ConnectionState> = _dashboardState

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _powerState = MutableStateFlow(PowerState.UNKNOWN)
    val powerState: StateFlow<PowerState> = _powerState

    private val _overallHealth = MutableStateFlow(HealthLevel.UNKNOWN)
    val overallHealth: StateFlow<HealthLevel> = _overallHealth

    /** Chassis locator LED; only IPMI reports it, so it stays false on the SSH path. */
    private val _identifyOn = MutableStateFlow(false)
    val identifyOn: StateFlow<Boolean> = _identifyOn

    private val _indicator = MutableStateFlow(HostIndicator.UNKNOWN)
    val indicator: StateFlow<HostIndicator> = _indicator

    private val _powerActionInProgress = MutableStateFlow(false)
    val powerActionInProgress: StateFlow<Boolean> = _powerActionInProgress

    private val _dashboardRefreshing = MutableStateFlow(false)
    val dashboardRefreshing: StateFlow<Boolean> = _dashboardRefreshing

    private val _hardware = MutableStateFlow<List<HardwareComponent>>(emptyList())
    val hardware: StateFlow<List<HardwareComponent>> = _hardware

    private val _hardwareLoading = MutableStateFlow(false)
    val hardwareLoading: StateFlow<Boolean> = _hardwareLoading

    /**
     * What the current long operation is doing. A full hardware scan runs one CLI command per
     * component against a slow BMC, so a bare spinner gives no sense of whether anything is
     * happening or how much is left.
     */
    val progress: StateFlow<String?> = ConnectionProgress.flowFor(host.id)

    private fun report(message: String?) = ConnectionProgress.report(host.id, message)

    var hardwareLoaded: Boolean = false
        private set

    private val _consoleTranscript = MutableStateFlow("")
    val consoleTranscript: StateFlow<String> = _consoleTranscript

    private val _consoleBusy = MutableStateFlow(false)
    val consoleBusy: StateFlow<Boolean> = _consoleBusy

    /**
     * Prepares the power dashboard. Over IPMI this deliberately does *not* open the SSH session:
     * establishing it costs seconds against this BMC, which is the very latency IPMI is there to
     * avoid — so SSH is left to the tabs that actually need the CLI (see [ensureSshConnected]).
     */
    fun connectAndLoadDashboard() {
        if (usesIpmi) {
            loadDashboardOverIpmi()
            return
        }
        if (_connectionState.value == ConnectionState.CONNECTED) {
            _dashboardState.value = ConnectionState.CONNECTED
            refreshDashboard()
            return
        }
        if (_connectionState.value == ConnectionState.CONNECTING) return
        _connectionState.value = ConnectionState.CONNECTING
        _dashboardState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        scope.launch {
            try {
                client.connect(host)
                _connectionState.value = ConnectionState.CONNECTED
                _dashboardState.value = ConnectionState.CONNECTED
                report("Récupération de l'état d'alimentation…")
                refreshDashboard()
                report(null)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Connexion impossible"
                _connectionState.value = ConnectionState.ERROR
                _dashboardState.value = ConnectionState.ERROR
            }
        }
    }

    private fun loadDashboardOverIpmi() {
        if (_dashboardState.value == ConnectionState.CONNECTING) return
        _dashboardState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        scope.launch {
            try {
                report("Session IPMI…")
                refreshDashboardOverIpmi()
                report(null)
                _dashboardState.value = ConnectionState.CONNECTED
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Connexion IPMI impossible"
                _dashboardState.value = ConnectionState.ERROR
            }
        }
    }

    /**
     * Opens the SSH control session if it isn't already up. Called by the tabs that need the iLO
     * CLI (console, hardware scan); the power dashboard doesn't when it runs over IPMI.
     */
    fun ensureSshConnected() {
        if (_connectionState.value == ConnectionState.CONNECTING || _connectionState.value == ConnectionState.CONNECTED) return
        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null
        scope.launch {
            try {
                client.connect(host)
                _connectionState.value = ConnectionState.CONNECTED
                report(null)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Connexion impossible"
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    /**
     * True when this host is configured to drive the power dashboard over IPMI instead of SSH.
     * A tunnel that cannot carry UDP rules IPMI out entirely, however it is configured.
     */
    val usesIpmi: Boolean
        get() = host.ipmiEnabled && host.password.isNotBlank() && HostTunnelManager.supportsUdp(host)

    fun refreshDashboard() {
        if (_dashboardRefreshing.value) return
        _dashboardRefreshing.value = true
        scope.launch {
            try {
                if (usesIpmi) refreshDashboardOverIpmi() else refreshDashboardOverSsh()
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Échec de rafraîchissement"
            } finally {
                _dashboardRefreshing.value = false
            }
        }
    }

    /**
     * When IPMI is enabled the dashboard uses it exclusively — no SSH commands at all. A single
     * Get Chassis Status returns both the power state and the chassis fault indicators, which
     * replaces the multi-command SSH scan of fans and power supplies that dominated refresh time.
     *
     * Note this is a coarser health signal than the SSH scan: it reports chassis-level faults
     * (cooling, power, drives) rather than the state of each individual component. The Matériel
     * tab still does the detailed per-component scan over SSH.
     */
    private suspend fun refreshDashboardOverIpmi() {
        val status = withIpmi { it.getChassisStatus() }
        _powerState.value = when (status.power) {
            ChassisPowerState.ON -> PowerState.ON
            ChassisPowerState.OFF -> PowerState.OFF
            ChassisPowerState.UNKNOWN -> PowerState.UNKNOWN
        }
        _overallHealth.value = when {
            status.hasCriticalFault -> HealthLevel.CRITICAL
            status.hasFault -> HealthLevel.DEGRADED
            else -> HealthLevel.OK
        }
        _identifyOn.value = status.identifyOn
        _indicator.value = HostIndicator.from(status)
    }

    private suspend fun refreshDashboardOverSsh() {
        _powerState.value = IloCliParser.parsePowerState(client.runCommand("power"))
        val components = QUICK_SCAN_CATEGORIES.flatMap { category -> fetchCategory(category) }
        _overallHealth.value = IloCliParser.overallHealth(components.map { it.health })
        _indicator.value = HostIndicator.from(_powerState.value, _overallHealth.value)
    }

    fun performPowerAction(action: PowerAction) {
        if (_powerActionInProgress.value) return
        _powerActionInProgress.value = true
        scope.launch {
            try {
                if (usesIpmi) {
                    withIpmi { it.chassisControl(action.ipmiControl) }
                } else {
                    client.runCommand("power ${action.cliArgument}")
                }
                // The BMC needs a moment before it reports the new state accurately.
                kotlinx.coroutines.delay(3_000)
                if (usesIpmi) refreshDashboardOverIpmi() else {
                    _powerState.value = IloCliParser.parsePowerState(client.runCommand("power"))
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Échec de la commande d'alimentation"
            } finally {
                _powerActionInProgress.value = false
            }
        }
    }

    /**
     * Switches the chassis locator LED.
     *
     * Both paths can *set* it — the CLI through start/stop on /system1/led1, verified against real
     * hardware by watching IPMI's identify bits change. Only IPMI can *read* it back, though:
     * /system1/led1 reports "enabledstate=enabled" whether the LED is lit or not, so over SSH the
     * caller must say which state it wants rather than toggling a state the app cannot observe.
     */
    fun setIdentify(on: Boolean) {
        if (_powerActionInProgress.value) return
        _powerActionInProgress.value = true
        scope.launch {
            try {
                if (usesIpmi) {
                    withIpmi { it.setIdentify(on) }
                    refreshDashboardOverIpmi()
                } else {
                    client.runCommand(if (on) "start /system1/led1" else "stop /system1/led1")
                }
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Échec de la commande UID"
            } finally {
                _powerActionInProgress.value = false
            }
        }
    }

    /** True when the LED state can be read back, which only IPMI offers. */
    val canReadIdentify: Boolean get() = usesIpmi

    /**
     * Opens a fresh IPMI session per call (there is no long-lived state worth keeping) and runs
     * [block]. Failures propagate rather than silently falling back to SSH: the host was
     * explicitly configured for IPMI, so quietly reverting to the slow path would hide a broken
     * configuration behind the very latency IPMI was enabled to avoid.
     */
    private fun <T> withIpmi(block: (IpmiLanClient) -> T): T {
        // IPMI is UDP, so it only reaches the host through a tunnel that carries UDP — WireGuard
        // does, an SSH jump host does not (which is why usesIpmi rules that case out entirely).
        val endpoint = HostTunnelManager.endpointFor(host, host.ipmiPort, udp = true)
        val ipmi = IpmiLanClient(endpoint.host, endpoint.port, host.username, host.password, host.ipmiPrivilege)
        return try {
            ipmi.open()
            block(ipmi)
        } finally {
            ipmi.close()
        }
    }

    /** True when the hardware tab reads IPMI sensors instead of walking the CLI tree. */
    val hardwareUsesIpmi: Boolean
        get() = host.hardwareOverIpmi && usesIpmi

    fun loadHardwareIfNeeded(force: Boolean = false) {
        if (_hardwareLoading.value) return
        if (hardwareLoaded && !force) return
        _hardwareLoading.value = true
        scope.launch {
            try {
                if (hardwareUsesIpmi) {
                    loadHardwareOverIpmi()
                    return@launch
                }
                withTimeout(HARDWARE_SCAN_TIMEOUT_MS) {
                    report("Inventaire des composants…")
                    val rootTargets = IloCliParser.parseTargets(client.runCommand("show /system1"))
                    val components = mutableListOf<HardwareComponent>()
                    var scanned = 0
                    for (target in rootTargets) {
                        scanned++
                        report("Lecture de $target ($scanned/${rootTargets.size})…")
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
                report(null)
            }
        }
    }

    /**
     * Builds the hardware list from IPMI sensors.
     *
     * The sensor repository describes the sensors and never changes unless the hardware does, so
     * it is read once per session and reused; refreshing then only re-reads the values, which
     * takes a couple of hundred milliseconds for the whole machine.
     */
    private fun loadHardwareOverIpmi() {
        withIpmi { client ->
            val reader = IpmiSensorReader(client)
            val entries = cachedSdr ?: reader.readRepository { scanned, total ->
                report("Lecture du répertoire de capteurs ($scanned/$total)…")
            }.also { cachedSdr = it }

            report("Lecture des capteurs…")
            val components = entries.mapNotNull { entry ->
                reader.readSensor(entry)?.let { sensor ->
                    HardwareComponent(
                        path = "ipmi/sensor/${sensor.number}",
                        category = categoryForSensorType(sensor.sensorType),
                        label = sensor.name,
                        health = when (sensor.health) {
                            SensorHealth.OK -> HealthLevel.OK
                            SensorHealth.DEGRADED -> HealthLevel.DEGRADED
                            SensorHealth.CRITICAL -> HealthLevel.CRITICAL
                            SensorHealth.UNAVAILABLE -> HealthLevel.UNKNOWN
                        },
                        properties = linkedMapOf(
                            "HealthState" to sensor.health.name,
                            "Mesure" to sensor.reading,
                        ),
                    )
                }
            }
            _hardware.value = components
            hardwareLoaded = true
        }
    }

    /** Maps IPMI sensor types onto the categories the hardware tab already groups by. */
    private fun categoryForSensorType(type: Int): String = when (type) {
        0x01 -> "sensor"
        0x04 -> "fan"
        0x07 -> "cpu"
        0x08, 0x09 -> "powersupply"
        0x0C -> "memory"
        0x0D -> "drives"
        else -> "other"
    }

    private var cachedSdr: List<SdrEntry>? = null

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
        cachedSdr = null
        _indicator.value = HostIndicator.UNKNOWN
        client.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _dashboardState.value = ConnectionState.DISCONNECTED
        hardwareLoaded = false
        _hardware.value = emptyList()
    }
}
