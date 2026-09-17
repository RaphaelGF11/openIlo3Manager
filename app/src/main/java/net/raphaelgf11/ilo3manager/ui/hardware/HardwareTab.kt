package net.raphaelgf11.ilo3manager.ui.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.raphaelgf11.ilo3manager.ilo.ControlSessionController
import net.raphaelgf11.ilo3manager.ilo.HardwareComponent
import net.raphaelgf11.ilo3manager.ssh.ConnectionState
import net.raphaelgf11.ilo3manager.ui.dashboard.HealthLed

private val CATEGORY_LABELS = mapOf(
    "fan" to "Ventilateurs",
    "sensor" to "Capteurs de température",
    "powersupply" to "Alimentations",
    "slot" to "Emplacements PCI",
    "network" to "Réseau",
    "firmware" to "Firmware",
    "bootconfig" to "Configuration de démarrage",
    "log" to "Journal",
    "led" to "LED",
)

private enum class HardwareSubTab(val title: String) {
    STATUS("État"),
    MEMORY("Mémoire"),
    CPU("CPU"),
    DRIVES("Disques"),
    OTHER("Autres"),
}

@Composable
fun HardwareTab(controller: ControlSessionController) {
    val connectionState by controller.connectionState.collectAsStateWithLifecycle()
    val loading by controller.hardwareLoading.collectAsStateWithLifecycle()
    val hardware by controller.hardware.collectAsStateWithLifecycle()
    val error by controller.errorMessage.collectAsStateWithLifecycle()
    var selectedSubTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.CONNECTED) {
            controller.loadHardwareIfNeeded()
        }
    }

    when {
        connectionState != ConnectionState.CONNECTED -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ouvrez l'onglet Alimentation pour vous connecter d'abord.")
            }
        }
        loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Chargement de l'état du matériel…")
                }
            }
        }
        else -> {
            // Drives get their own tab (including any disk nested under a drive bay), memory and
            // CPU get dedicated tabs matching the iLO web UI layout, "État" covers everything else
            // that reports a HealthState (fans, sensors, PSUs, PCI slots...), and anything left
            // over (firmware, network, log...) lands in "Autres" so nothing silently disappears.
            val drives = hardware.filter { it.path.startsWith("/system1/drives") }
            val memory = hardware.filter { it.category == "memory" }
            val cpu = hardware.filter { it.category == "cpu" }
            val status = hardware.filter { it.hasHealthState && it !in drives }
            val other = hardware.filterNot { it in drives || it in memory || it in cpu || it in status }

            val tabContents = mapOf(
                HardwareSubTab.STATUS to status,
                HardwareSubTab.MEMORY to memory,
                HardwareSubTab.CPU to cpu,
                HardwareSubTab.DRIVES to drives,
                HardwareSubTab.OTHER to other,
            )

            Column(modifier = Modifier.fillMaxSize()) {
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                }
                Row(modifier = Modifier.padding(8.dp)) {
                    Button(onClick = { controller.loadHardwareIfNeeded(force = true) }) {
                        Text("Rafraîchir")
                    }
                }
                ScrollableTabRow(selectedTabIndex = selectedSubTab) {
                    HardwareSubTab.entries.forEachIndexed { index, subTab ->
                        Tab(
                            selected = selectedSubTab == index,
                            onClick = { selectedSubTab = index },
                            text = { Text("${subTab.title} (${tabContents[subTab]?.size ?: 0})") },
                        )
                    }
                }

                val components = tabContents[HardwareSubTab.entries[selectedSubTab]].orEmpty()
                if (components.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Aucun élément dans cette catégorie.")
                    }
                } else {
                    val grouped = components.groupBy { it.category }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        grouped.forEach { (category, categoryComponents) ->
                            if (grouped.size > 1) {
                                item {
                                    Text(
                                        text = CATEGORY_LABELS[category] ?: category,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                    )
                                }
                            }
                            items(categoryComponents, key = { it.path }) { component ->
                                HardwareComponentCard(component)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareComponentCard(component: HardwareComponent) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (component.hasHealthState) {
                HealthLed(health = component.health)
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(component.label, style = MaterialTheme.typography.bodyLarge)
                val details = component.properties
                    .filterKeys { !it.equals("HealthState", ignoreCase = true) && !it.equals("ElementName", ignoreCase = true) }
                    .entries
                    .joinToString("  •  ") { "${it.key}: ${it.value}" }
                if (details.isNotBlank()) {
                    Text(details, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
