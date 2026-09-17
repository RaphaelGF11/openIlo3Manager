package net.raphaelgf11.ilo3manager.ui.vsp

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
import androidx.compose.material3.CircularProgressIndicator
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
import net.raphaelgf11.ilo3manager.ilo.VspSessionController
import net.raphaelgf11.ilo3manager.ssh.ConnectionState

@Composable
fun VspTab(controller: VspSessionController) {
    val output by controller.output.collectAsStateWithLifecycle()
    val state by controller.connectionState.collectAsStateWithLifecycle()
    val error by controller.errorMessage.collectAsStateWithLifecycle()
    var command by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        controller.connectIfNeeded()
    }

    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Box(modifier = Modifier
        .fillMaxSize()
        .imePadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            when (state) {
                ConnectionState.CONNECTING -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Connexion au port série virtuel…")
                }
                ConnectionState.ERROR, ConnectionState.DISCONNECTED -> {
                    Text(
                        text = error ?: "Non connecté",
                        modifier = Modifier.padding(8.dp),
                    )
                }
                ConnectionState.CONNECTED -> {}
            }

            Text(
                text = output,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(8.dp),
            )

            if (state == ConnectionState.CONNECTED) {
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
                        label = { Text("Commande") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                controller.sendCommand(command)
                                command = ""
                            },
                        ),
                    )
                    IconButton(onClick = {
                        controller.sendCommand(command)
                        command = ""
                    }) {
                        Icon(Icons.Filled.Send, contentDescription = "Envoyer")
                    }
                }
            } else if (state != ConnectionState.CONNECTING) {
                Button(
                    onClick = { controller.reconnect() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                ) {
                    Text("Reconnexion")
                }
            }
        }
    }
}
