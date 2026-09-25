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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import net.raphaelgf11.ilo3manager.R
import net.raphaelgf11.ilo3manager.data.NotificationSettingsRepository
import net.raphaelgf11.ilo3manager.data.SshHost
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.ilo.HostIndicator
import net.raphaelgf11.ilo3manager.ilo.HostStateProbe
import net.raphaelgf11.ilo3manager.ssh.ConnectionState
import net.raphaelgf11.ilo3manager.ssh.HostSessionStore
import net.raphaelgf11.ilo3manager.webgateway.WebGatewayManager
import net.raphaelgf11.ilo3manager.ui.common.ConfirmDeleteDialog
import net.raphaelgf11.ilo3manager.ui.dashboard.StatusDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostListScreen(
    viewModel: HostListViewModel,
    notificationSettingsRepository: NotificationSettingsRepository,
    onAddHost: () -> Unit,
    onOpenHost: (SshHost) -> Unit,
    onEditHost: (SshHost) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetworks: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    // Bumped on each pull, which restarts every row's polling effect so states are re-read now
    // rather than at the end of their normal interval.
    var refreshTick by remember { mutableIntStateOf(0) }
    val hosts by viewModel.hosts.collectAsState()
    var editMode by remember { mutableStateOf(false) }
    var notificationDialogHost by remember { mutableStateOf<SshHost?>(null) }
    var pendingDeletion by remember { mutableStateOf<SshHost?>(null) }
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
                        // The LAN symbol from the server's own silkscreen, the same one the
                        // front-panel widget draws beside each port. The bundled icon set has no
                        // network glyph, and its nearest — the node graph — reads as "share".
                        IconButton(onClick = onOpenNetworks) {
                            Icon(
                                painter = painterResource(R.drawable.ic_network),
                                contentDescription = "Réseaux",
                            )
                        }
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
                // One way in. It opens the assistant, which reads the server's own settings
                // rather than asking the user for them, and which offers the manual form to anyone
                // it cannot reach — two buttons here only made the user choose before knowing
                // which one applied.
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
                // Centring the box does not inset the text: without a margin the sentence wraps
                // against both screen edges and its first letters are clipped.
                Text(
                    "Aucun hôte enregistré. Appuyez sur + pour en ajouter un.",
                    modifier = Modifier.padding(horizontal = 32.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            // Pull to refresh: re-reads the stored hosts and lets each row's poller take a fresh
            // reading, the same gesture a browser uses for the same intent.
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    viewModel.refresh()
                    refreshTick++
                    scope.launch {
                        delay(600)
                        refreshing = false
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(hosts, key = { it.id }) { host ->
                    val index = hosts.indexOf(host)
                    val isDragging = draggingId == host.id
                    HostRow(
                        host = host,
                        refreshTick = refreshTick,
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
                        onDelete = { pendingDeletion = host },
                        onDisconnect = { viewModel.disconnectHost(context, host.id) },
                        onOpenNotificationSettings = { notificationDialogHost = host },
                    )
                }
            }
            }
        }
    }

    pendingDeletion?.let { host ->
        ConfirmDeleteDialog(
            title = "Supprimer « ${host.name} » ?",
            message = "Son compte, sa clé et ses réglages seront effacés définitivement. " +
                "Le serveur lui-même n'est pas touché.",
            onConfirm = {
                viewModel.deleteHost(host.id)
                pendingDeletion = null
            },
            onDismiss = { pendingDeletion = null },
        )
    }
}

/** Slow enough not to hammer several BMCs, fast enough to notice a machine going down. */
private const val LIST_POLL_INTERVAL_MS = 30_000L

@Composable
private fun HostRow(
    host: SshHost,
    refreshTick: Int,
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
    // Re-read the store whenever a session appears or disappears: holding the first lookup meant
    // a row composed before the session existed never reflected it.
    val revision by HostSessionStore.revision.collectAsState()
    val controlSession = remember(host.id, revision) { HostSessionStore.existingControlSessionFor(host.id) }
    val noSession = remember { MutableStateFlow(ConnectionState.DISCONNECTED) }
    val unknownIndicator = remember { MutableStateFlow(HostIndicator.UNKNOWN) }
    val sessionState by (controlSession?.connectionState ?: noSession).collectAsState()
    val dashboardStateValue by (controlSession?.dashboardState ?: noSession).collectAsState()
    val liveIndicator by (controlSession?.indicator ?: unknownIndicator).collectAsState()

    val runningGateways by WebGatewayManager.runningHostIds.collectAsState()
    // A running gateway keeps a foreground service and a listening port open, so the host is just
    // as "in use" as one with an SSH session — and the X that stops it must stay reachable.
    val sessionActive = sessionState == ConnectionState.CONNECTED || dashboardStateValue == ConnectionState.CONNECTED
    val active = sessionActive || sessionState == ConnectionState.CONNECTING ||
        dashboardStateValue == ConnectionState.CONNECTING || host.id in runningGateways
    val retrieving = sessionState == ConnectionState.CONNECTING ||
        dashboardStateValue == ConnectionState.CONNECTING

    var polledIndicator by remember(host.id) { mutableStateOf(HostIndicator.UNKNOWN) }
    // A probe opens an IPMI session and reads the chassis, which takes a moment; showing that it
    // is under way distinguishes "still asking" from "asked, and the answer is unknown".
    var probing by remember(host.id) { mutableStateOf(false) }
    LaunchedEffect(host.id, host.showStateInList, host.ipmiEnabled, refreshTick) {
        if (!HostStateProbe.isPollable(host)) return@LaunchedEffect
        while (true) {
            probing = true
            polledIndicator = withContext(Dispatchers.IO) { HostStateProbe.probe(host) }
            probing = false
            delay(LIST_POLL_INTERVAL_MS)
        }
    }

    // Yellow covers every "we are finding out" case. A session being open says nothing about the
    // machine's power state, so it must not show green: until a state has actually been read the
    // honest answer is still "retrieving".
    val indicator = when {
        retrieving || probing -> HostIndicator.CONNECTING
        liveIndicator != HostIndicator.UNKNOWN -> liveIndicator
        sessionActive -> HostIndicator.CONNECTING
        else -> polledIndicator
    }


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
            }

            // The dot sits on the name line rather than beside the whole block, so it never
            // shifts the "user@host" line underneath it.
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(indicator = indicator)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(start = 6.dp))
                    Text(host.name, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    if (host.webGatewayOnly) "${host.hostname}:${host.httpsPort} — passerelle web"
                    else "${host.username}@${host.hostname}:${host.port}",
                )
            }

            if (editMode) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Éditer")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                }
            } else {
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
