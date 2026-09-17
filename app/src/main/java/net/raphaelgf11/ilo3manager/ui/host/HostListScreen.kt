package net.raphaelgf11.ilo3manager.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.raphaelgf11.ilo3manager.data.NotificationSettingsRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ssh.HostSessionStore
import net.raphaelgf11.ilo3manager.ui.notifications.NotificationSettingsDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    viewModel: HostListViewModel,
    notificationSettingsRepository: NotificationSettingsRepository,
    onAddHost: () -> Unit,
    onOpenHost: (SshHost) -> Unit,
    onEditHost: (SshHost) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hosts by viewModel.hosts.collectAsState()
    var editMode by remember { mutableStateOf(false) }
    var notificationDialogHost by remember { mutableStateOf<SshHost?>(null) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggingIndex by remember { mutableStateOf(0) }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<String, Int>() }
    val spacingPx = with(LocalDensity.current) { 8.dp.toPx() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("iLO3 Manager") },
                actions = {
                    if (!editMode) {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Réglages")
                        }
                    }
                    IconButton(onClick = { editMode = !editMode }) {
                        Icon(
                            if (editMode) Icons.Filled.Check else Icons.Filled.Edit,
                            contentDescription = if (editMode) "Terminer l'édition" else "Modifier la liste",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (!editMode) {
                FloatingActionButton(onClick = onAddHost) {
                    Icon(Icons.Filled.Add, contentDescription = "Ajouter un hôte")
                }
            }
        },
    ) { padding ->
        if (hosts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Aucun hôte enregistré. Appuyez sur + pour en ajouter un.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(hosts, key = { it.id }) { host ->
                    val index = hosts.indexOf(host)
                    val isDragging = draggingId == host.id
                    HostRow(
                        host = host,
                        editMode = editMode,
                        isDragging = isDragging,
                        offsetY = if (isDragging) dragAccumulated else 0f,
                        onMeasuredHeight = { height -> itemHeights[host.id] = height },
                        onDragStart = {
                            draggingId = host.id
                            draggingIndex = index
                            dragAccumulated = 0f
                        },
                        onDragDelta = { delta ->
                            dragAccumulated += delta
                            val itemHeight = itemHeights[host.id]?.toFloat() ?: return@HostRow
                            val slot = itemHeight + spacingPx
                            // Truncate toward zero: only commit a swap once the finger has
                            // crossed a full row (item height + list spacing), otherwise the
                            // dragged row drifts from the finger by the accumulated spacing error.
                            val moveBy = (dragAccumulated / slot).toInt()
                            if (moveBy != 0) {
                                val newIndex = (draggingIndex + moveBy).coerceIn(0, hosts.lastIndex)
                                val actualMove = newIndex - draggingIndex
                                if (actualMove != 0) {
                                    viewModel.moveHost(draggingIndex, newIndex)
                                    dragAccumulated -= actualMove * slot
                                    draggingIndex = newIndex
                                }
                            }
                        },
                        onDragEnd = {
                            draggingId = null
                            dragAccumulated = 0f
                        },
                        onClick = { if (!editMode) onOpenHost(host) },
                        onEdit = { onEditHost(host) },
                        onDelete = { viewModel.deleteHost(host.id) },
                        onDisconnect = { viewModel.disconnectHost(host.id) },
                        onOpenNotificationSettings = { notificationDialogHost = host },
                    )
                }
            }
        }
    }

    notificationDialogHost?.let { host ->
        NotificationSettingsDialog(
            hostId = host.id,
            hostName = host.name,
            repository = notificationSettingsRepository,
            onDismiss = { notificationDialogHost = null },
        )
    }
}

@Composable
private fun HostRow(
    host: SshHost,
    editMode: Boolean,
    isDragging: Boolean,
    offsetY: Float,
    onMeasuredHeight: (Int) -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val active by HostSessionStore.activeFlow(host.id).collectAsState(initial = false)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .onGloballyPositioned { onMeasuredHeight(it.size.height) }
            .graphicsLayer { translationY = offsetY }
            .zIndex(if (isDragging) 1f else 0f)
            .clickable(enabled = !editMode) { onClick() },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (editMode) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = "Réordonner",
                    modifier = Modifier.pointerInput(host.id) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDragDelta(dragAmount.y)
                            },
                        )
                    },
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 8.dp))
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (active) Color(0xFF3DDC84) else Color(0xFF9E9E9E),
                            shape = CircleShape,
                        ),
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 6.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(host.name, style = MaterialTheme.typography.titleMedium)
                Text("${host.username}@${host.hostname}:${host.port}")
            }

            if (editMode) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Éditer")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                }
            } else {
                IconButton(onClick = onOpenNotificationSettings) {
                    Icon(Icons.Filled.Notifications, contentDescription = "Notifications")
                }
                if (active) {
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.Filled.Close, contentDescription = "Interrompre la connexion")
                    }
                }
                IconButton(onClick = onClick) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Se connecter")
                }
            }
        }
    }
}
