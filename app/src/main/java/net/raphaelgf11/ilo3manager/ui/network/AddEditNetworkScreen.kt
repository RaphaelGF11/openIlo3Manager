package net.raphaelgf11.ilo3manager.ui.network

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.raphaelgf11.ilo3manager.data.NetworkConfig
import net.raphaelgf11.ilo3manager.data.NetworkRepository
import net.raphaelgf11.ilo3manager.data.NetworkType
import net.raphaelgf11.ilo3manager.ui.common.RadioRow
import net.raphaelgf11.ilo3manager.ui.host.PortraitCaptureActivity
import net.raphaelgf11.ilo3manager.vpn.SecondaryAddressManager
import net.raphaelgf11.ilo3manager.vpn.SshTunnelConfig
import net.raphaelgf11.ilo3manager.vpn.isValidCidr
import net.raphaelgf11.ilo3manager.vpn.isValidInterfaceName
import net.raphaelgf11.ilo3manager.vpn.WireGuardConfigParser
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditNetworkScreen(
    repository: NetworkRepository,
    existingNetwork: NetworkConfig?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(existingNetwork?.name ?: "") }
    var type by remember { mutableStateOf(existingNetwork?.type ?: NetworkType.WIREGUARD) }
    // One field per type rather than one shared: switching type to compare two options must not
    // discard the configuration already entered for the first.
    var wireGuardConfig by remember { mutableStateOf(existingNetwork?.wireGuardConfig ?: "") }
    var sshTunnelConfig by remember { mutableStateOf(existingNetwork?.sshTunnelConfig ?: "") }
    var interfaceName by remember { mutableStateOf(existingNetwork?.interfaceName ?: "") }
    var secondaryAddress by remember { mutableStateOf(existingNetwork?.secondaryAddress ?: "") }

    // A WireGuard QR code encodes the .conf text verbatim, so a scan feeds the same parser as a
    // pasted or imported configuration.
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { wireGuardConfig = it }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                wireGuardConfig = BufferedReader(InputStreamReader(stream)).readText()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existingNetwork == null) "Nouveau réseau" else "Modifier le réseau") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = {
                        repository.saveNetwork(
                            NetworkConfig(
                                id = existingNetwork?.id ?: java.util.UUID.randomUUID().toString(),
                                name = name,
                                type = type,
                                wireGuardConfig = wireGuardConfig,
                                sshTunnelConfig = sshTunnelConfig,
                                interfaceName = interfaceName,
                                secondaryAddress = secondaryAddress,
                            ),
                        )
                        onDone()
                    },
                    // Only the name is required: a network can be created and filled in later, and
                    // the list marks the incomplete ones rather than blocking the save.
                    enabled = name.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(16.dp),
                ) {
                    Text("Enregistrer")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom du réseau") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer()
            Text(
                "Ce nom est ce que vous choisirez dans l'onglet Général d'un serveur. Plusieurs " +
                    "serveurs peuvent partager le même réseau : ils partageront alors un seul tunnel.",
                style = MaterialTheme.typography.bodySmall,
            )

            Spacer()
            Text("Type", style = MaterialTheme.typography.titleSmall)
            NetworkType.entries.forEach { candidate ->
                RadioRow(
                    selected = type == candidate,
                    label = candidate.label,
                    detail = candidate.detail,
                    onSelect = { type = candidate },
                )
            }

            Spacer()
            when (type) {
                NetworkType.WIREGUARD -> WireGuardEditor(
                    config = wireGuardConfig,
                    onConfigChange = { wireGuardConfig = it },
                    onImportFile = { filePicker.launch("*/*") },
                    onScanQrCode = {
                        qrScanner.launch(ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("Scannez le QR code WireGuard")
                            setBeepEnabled(false)
                            setCaptureActivity(PortraitCaptureActivity::class.java)
                        })
                    },
                )
                NetworkType.SSH_TUNNEL -> SshTunnelEditor(
                    config = sshTunnelConfig,
                    onConfigChange = { sshTunnelConfig = it },
                )
                NetworkType.SECONDARY_IP -> SecondaryIpEditor(
                    interfaceName = interfaceName,
                    onInterfaceNameChange = { interfaceName = it },
                    address = secondaryAddress,
                    onAddressChange = { secondaryAddress = it },
                )
            }
        }
    }
}

@Composable
private fun WireGuardEditor(
    config: String,
    onConfigChange: (String) -> Unit,
    onImportFile: () -> Unit,
    onScanQrCode: () -> Unit,
) {
    Row {
        OutlinedButton(onClick = onImportFile) {
            Text("Importer un .conf")
        }
        OutlinedButton(onClick = onScanQrCode, modifier = Modifier.padding(start = 8.dp)) {
            Text("Scanner un QR code")
        }
    }
    Spacer()
    OutlinedTextField(
        value = config,
        onValueChange = onConfigChange,
        label = { Text("Configuration WireGuard (collez, importez ou scannez)") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 6,
    )
    Spacer()

    // Parse as the user types so a malformed file is caught here rather than at connection time,
    // and so the summary confirms the app read what the user expected.
    if (config.isNotBlank()) {
        runCatching { WireGuardConfigParser.parse(config) }.fold(
            onSuccess = { wg ->
                Text("Configuration valide", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Pair : ${wg.endpoint}\n" +
                        "Adresse locale : ${wg.addresses.joinToString(", ")}\n" +
                        "Réseaux routés : ${wg.allowedIps.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            onFailure = { error ->
                Text(
                    error.message ?: "Configuration illisible",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
        )
    }
}

@Composable
private fun SshTunnelEditor(config: String, onConfigChange: (String) -> Unit) {
    val tunnel = remember(config) { SshTunnelConfig.fromJson(config) }
    fun update(block: SshTunnelConfig.() -> SshTunnelConfig) {
        onConfigChange(tunnel.block().toJson())
    }

    Text(
        "Les connexions vers l'iLO sont relayées par cette machine intermédiaire.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer()
    OutlinedTextField(
        value = tunnel.host,
        onValueChange = { v -> update { copy(host = v) } },
        label = { Text("Hôte de rebond") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = tunnel.port.toString(),
        onValueChange = { v -> update { copy(port = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 22) } },
        label = { Text("Port SSH du rebond") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = tunnel.username,
        onValueChange = { v -> update { copy(username = v) } },
        label = { Text("Utilisateur du rebond") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = tunnel.password,
        onValueChange = { v -> update { copy(password = v) } },
        label = { Text("Mot de passe (ou laissez vide et collez une clé)") },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = tunnel.privateKey,
        onValueChange = { v -> update { copy(privateKey = v) } },
        label = { Text("Clé privée du rebond (optionnel)") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
    )
    Spacer()
    Text(
        "Le rebond SSH ne transporte que du TCP. Les serveurs sur ce réseau ne pourront donc pas " +
            "utiliser IPMI, qui fonctionne en UDP, et repasseront automatiquement par la CLI SSH.",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun SecondaryIpEditor(
    interfaceName: String,
    onInterfaceNameChange: (String) -> Unit,
    address: String,
    onAddressChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Result<String>?>(null) }
    // Read once: the list changes when Wi-Fi comes and goes, but naming one the phone does not
    // have is the mistake worth catching, and that list is stable enough while this screen is open.
    val interfaces = remember { SecondaryAddressManager.interfaceNames() }

    OutlinedTextField(
        value = interfaceName,
        onValueChange = onInterfaceNameChange,
        label = { Text("Interface") },
        isError = interfaceName.isNotBlank() && !isValidInterfaceName(interfaceName),
        supportingText = {
            Text(
                if (interfaces.isEmpty()) "Par exemple wlan0."
                else "Sur ce téléphone : ${interfaces.joinToString(", ")}",
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    OutlinedTextField(
        value = address,
        onValueChange = onAddressChange,
        label = { Text("Adresse à ajouter, en notation CIDR") },
        isError = address.isNotBlank() && !isValidCidr(address),
        supportingText = {
            Text(
                if (address.isNotBlank() && !isValidCidr(address)) {
                    "Adresse IPv4 avec préfixe attendue, par exemple 192.168.1.9/24."
                } else {
                    "Par exemple 192.168.1.9/24, sur le même sous-réseau que le serveur."
                },
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer()
    Text(
        "Contrairement aux tunnels, cette adresse est une vraie adresse que le réseau peut " +
            "joindre en retour, ce dont certains protocoles ont besoin — les traps SNMP de " +
            "l'iLO en particulier. L'application la pose sur l'interface à chaque connexion, " +
            "ce qui la fait survivre à un redémarrage ou à une reconnexion du Wi-Fi.",
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer()
    OutlinedButton(
        onClick = {
            busy = true
            outcome = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    SecondaryAddressManager.ensureApplied(
                        NetworkConfig(
                            name = "",
                            type = NetworkType.SECONDARY_IP,
                            interfaceName = interfaceName,
                            secondaryAddress = address,
                        ),
                    )
                }
                outcome = result.map { "Adresse posée sur $interfaceName." }
                busy = false
            }
        },
        enabled = !busy && isValidInterfaceName(interfaceName) && isValidCidr(address),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (busy) "Demande de root en cours…" else "Poser l'adresse maintenant")
    }

    outcome?.let { result ->
        Spacer()
        Text(
            result.fold(
                onSuccess = { it },
                onFailure = {
                    // The ordinary case on an unrooted phone, and worth naming as such rather
                    // than leaving the user to read a shell error.
                    it.message ?: "Root refusé ou indisponible sur ce téléphone."
                },
            ),
            color = if (result.isFailure) MaterialTheme.colorScheme.error else Color.Unspecified,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer()
    Text(
        "Nécessite le root : aucune API Android n'expose la configuration d'adresses. Sans lui, " +
            "ce type de réseau ne peut pas fonctionner.",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun Spacer() {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
}
