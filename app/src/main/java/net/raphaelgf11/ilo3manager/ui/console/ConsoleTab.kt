package net.raphaelgf11.ilo3manager.ui.console

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.raphaelgf11.ilo3manager.ilo.ControlSessionController
import net.raphaelgf11.ilo3manager.ssh.ConnectionState

/** Lets the user run arbitrary iLO CLI commands over the already-open control session. */
@Composable
fun ConsoleTab(controller: ControlSessionController) {
    val connectionState by controller.connectionState.collectAsStateWithLifecycle()
    val transcript by controller.consoleTranscript.collectAsStateWithLifecycle()
    val busy by controller.consoleBusy.collectAsStateWithLifecycle()
    val progress by controller.progress.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    LaunchedEffect(transcript) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    // The CLI genuinely needs SSH, so this tab opens the session itself: the power dashboard no
    // longer does when the host runs over IPMI.
    LaunchedEffect(controller) {
        controller.ensureSshConnected()
    }

    if (connectionState != ConnectionState.CONNECTED) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (connectionState) {
                ConnectionState.CONNECTING -> Text(progress ?: "Connexion SSH en cours…")
                else -> Button(onClick = { controller.ensureSshConnected() }) {
                    Text("Se connecter en SSH")
                }
            }
        }
        return
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = transcript,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Commande iLO CLI") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            controller.runConsoleCommand(command)
                            command = ""
                        },
                    ),
                )
                IconButton(
                    onClick = {
                        controller.runConsoleCommand(command)
                        command = ""
                    },
                    enabled = !busy,
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Envoyer")
                }
            }
        }
    }
}
