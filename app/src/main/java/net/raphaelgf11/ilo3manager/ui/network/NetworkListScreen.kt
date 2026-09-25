package net.raphaelgf11.ilo3manager.ui.network

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.NetworkConfig
import net.raphaelgf11.ilo3manager.data.NetworkRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ui.common.ConfirmDeleteDialog

/**
 * The named networks hosts can be reached through.
 *
 * Each row says how many servers use the network, which is the number that makes deleting one a
 * decision rather than a reflex: a network is shared, so removing it sends every one of those
 * servers back to a direct connection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkListScreen(
    networkRepository: NetworkRepository,
    hostRepository: HostRepository,
    onBack: () -> Unit,
    onAddNetwork: () -> Unit,
    onEditNetwork: (NetworkConfig) -> Unit,
) {
    // Re-read after every change rather than held in a view model: this list is short, edited
    // rarely, and returning to it from the edit screen has to show the edit.
    var revision by remember { mutableStateOf(0) }
    val networks = remember(revision) { networkRepository.getNetworks() }
    val hosts = remember(revision) { hostRepository.getHosts() }
    var pendingDeletion by remember { mutableStateOf<NetworkConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réseaux") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddNetwork) {
                Icon(Icons.Filled.Add, contentDescription = "Ajouter un réseau")
            }
        },
    ) { padding ->
        if (networks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Aucun réseau. Appuyez sur + pour en ajouter un, puis choisissez-le dans " +
                        "l'onglet Général d'un serveur.",
                    modifier = Modifier.padding(horizontal = 32.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(networks, key = { it.id }) { network ->
                    NetworkRow(
                        network = network,
                        users = hosts.filter { it.networkId == network.id },
                        onClick = { onEditNetwork(network) },
                        onDelete = { pendingDeletion = network },
                    )
                }
            }
        }
    }

    pendingDeletion?.let { network ->
        val users = hosts.filter { it.networkId == network.id }
        ConfirmDeleteDialog(
            title = "Supprimer « ${network.name} » ?",
            message = buildString {
                append("La configuration et ses clés seront effacées définitivement.")
                if (users.isNotEmpty()) {
                    // Naming them: the consequence lands on servers the user is not looking at.
                    append("\n\n")
                    append(
                        if (users.size == 1) "Ce serveur repassera en accès direct : "
                        else "Ces ${users.size} serveurs repasseront en accès direct : ",
                    )
                    append(users.joinToString(", ") { it.name })
                    append(".")
                }
            },
            onConfirm = {
                networkRepository.deleteNetwork(network.id, hostRepository)
                pendingDeletion = null
                revision++
            },
            onDismiss = { pendingDeletion = null },
        )
    }
}

@Composable
private fun NetworkRow(
    network: NetworkConfig,
    users: List<SshHost>,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(network.name, style = MaterialTheme.typography.titleMedium)
                Text(network.type.label, style = MaterialTheme.typography.bodySmall)
                Text(
                    when {
                        users.isEmpty() -> "Aucun serveur"
                        users.size == 1 -> users.first().name
                        else -> "${users.size} serveurs : ${users.joinToString(", ") { it.name }}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                // Saving an incomplete network is allowed, so the list is where that shows —
                // otherwise the only symptom is a connection failing much later.
                if (!network.isUsable) {
                    Text(
                        "Configuration incomplète",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
            }
        }
    }
}
