package net.raphaelgf11.ilo3manager.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import net.raphaelgf11.ilo3manager.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import net.raphaelgf11.ilo3manager.data.HostRepository
import net.raphaelgf11.ilo3manager.data.SettingsRepository

private val REFRESH_INTERVAL_OPTIONS = listOf(15, 30, 60, 120)
private val IPMI_DIVIDER_OPTIONS = listOf(2, 3, 5, 10)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: SettingsRepository,
    hostRepository: HostRepository,
    onBack: () -> Unit,
) {
    val settings by repository.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The activity draws edge to edge, which stops `adjustResize` from shrinking the
                // window: without this the keyboard simply covers whatever field has focus, and the
                // backup passphrase ends up being typed blind.
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.settings_refresh_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_refresh_description),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp, top = 4.dp),
            )
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                REFRESH_INTERVAL_OPTIONS.forEach { seconds ->
                    FilterChip(
                        selected = settings.autoRefreshSeconds == seconds,
                        onClick = { repository.setAutoRefreshSeconds(seconds) },
                        label = { Text(if (seconds < 60) "${seconds}s" else "${seconds / 60}min") },
                    )
                }
            }

            // An IPMI reading is a UDP round trip on an open session; the SSH path runs a command
            // and parses its output. Holding both to one interval means either polling the iLO's
            // shell too hard or letting IPMI idle for no reason.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(stringResource(R.string.settings_ipmi_faster))
                    Text(
                        stringResource(R.string.settings_ipmi_faster_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = settings.fasterRefreshOverIpmi,
                    onCheckedChange = { repository.setFasterRefreshOverIpmi(it) },
                )
            }

            if (settings.fasterRefreshOverIpmi) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    IPMI_DIVIDER_OPTIONS.forEach { divider ->
                        FilterChip(
                            selected = settings.ipmiRefreshDivider == divider,
                            onClick = { repository.setIpmiRefreshDivider(divider) },
                            label = { Text("/$divider") },
                        )
                    }
                }
                val effective = settings.refreshSecondsFor(usesIpmi = true)
                Text(
                    stringResource(R.string.settings_ipmi_effective, effective, settings.autoRefreshSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            UpdateSection(settings = repository)

            DriveSyncSection(hostRepository = hostRepository)
        }
    }
}
