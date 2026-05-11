package com.terminalmobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminalmobile.data.ConnectionConfig
import com.terminalmobile.data.PreferencesRepository
import com.terminalmobile.network.WsState
import com.terminalmobile.ui.theme.*
import com.terminalmobile.viewmodel.TerminalViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: TerminalViewModel,
    onConnected: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val config by viewModel.prefs.configFlow.collectAsState(initial = ConnectionConfig())
    val uiState by viewModel.ui.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current

    var host by remember(config.host) { mutableStateOf(config.host) }
    var port by remember(config.port) { mutableStateOf(config.port.toString()) }
    var token by remember(config.token) { mutableStateOf(config.token) }
    var useTls by remember(config.useTls) { mutableStateOf(config.useTls) }
    var showToken by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState is WsState.Connected) onConnected()
    }

    val isConnecting = uiState.connectionState is WsState.Connecting
        || uiState.connectionState is WsState.Reconnecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            text = "TerminalMobile",
            fontFamily = FontFamily.Monospace,
            fontSize = 26.sp,
            color = TerminalGreen,
        )
        Text(
            text = "Remote Claude Code Terminal",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = TerminalDim,
        )

        Spacer(Modifier.height(40.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TerminalSurface),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {

                Text("Connection", fontFamily = FontFamily.Monospace, color = TerminalDim, fontSize = 12.sp)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host / IP", fontFamily = FontFamily.Monospace) },
                        placeholder = { Text("192.168.1.100", color = TerminalDim, fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        colors = terminalFieldColors(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) port = it },
                        label = { Text("Port", fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.width(100.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        colors = terminalFieldColors(),
                        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    )
                }

                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Auth Token", fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = TerminalDim,
                            )
                        }
                    },
                    colors = terminalFieldColors(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = useTls,
                        onCheckedChange = { useTls = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TerminalGreen, checkedTrackColor = TerminalGreen.copy(alpha = 0.3f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Use TLS (wss://)", fontFamily = FontFamily.Monospace, color = TerminalText, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        val statusText = when (val s = uiState.connectionState) {
            is WsState.Connecting -> "Connecting..."
            is WsState.Reconnecting -> "Reconnecting (attempt ${s.attempt})..."
            is WsState.Error -> "Error: ${s.message}"
            else -> null
        }
        if (statusText != null) {
            Text(statusText, fontFamily = FontFamily.Monospace, color = TerminalYellow, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                keyboard?.hide()
                val cfg = ConnectionConfig(
                    host = host.trim(),
                    port = port.toIntOrNull() ?: 8765,
                    token = token.trim(),
                    useTls = useTls,
                )
                scope.launch { viewModel.prefs.save(cfg) }
                viewModel.connect(cfg)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = host.isNotBlank() && token.isNotBlank() && !isConnecting,
            colors = ButtonDefaults.buttonColors(containerColor = TerminalGreen, contentColor = TerminalBg),
        ) {
            if (isConnecting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TerminalBg, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Icons.Default.Terminal, contentDescription = null)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isConnecting) "Connecting..." else "Connect", fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Server: node server/start.bat\nToken shown on first run",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TerminalDim,
        )
    }
}

@Composable
private fun terminalFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = TerminalGreen,
    unfocusedBorderColor = TerminalBorder,
    focusedLabelColor = TerminalGreen,
    unfocusedLabelColor = TerminalDim,
    cursorColor = TerminalGreen,
    focusedTextColor = TerminalText,
    unfocusedTextColor = TerminalText,
)
