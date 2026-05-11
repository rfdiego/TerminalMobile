package com.terminalmobile.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminalmobile.network.*
import com.terminalmobile.ui.theme.*
import com.terminalmobile.viewmodel.LineType
import com.terminalmobile.viewmodel.TerminalLine
import com.terminalmobile.viewmodel.TerminalViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onDisconnect: () -> Unit,
) {
    val uiState by viewModel.ui.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showSessions by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    val totalLines = uiState.systemLines.size + uiState.ptyLines.size
    LaunchedEffect(totalLines) {
        if (uiState.autoScroll && totalLines > 0) {
            listState.animateScrollToItem(totalLines - 1)
        }
    }

    LaunchedEffect(uiState.connectionState) {
        if (uiState.connectionState is WsState.Error) onDisconnect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
    ) {
        StatusBar(
            state = uiState.connectionState,
            sessionId = uiState.currentSessionId,
            sessionCount = uiState.sessions.size,
            onMenuClick = { showMenu = true },
            onSessionsClick = { showSessions = true },
        )

        Box(modifier = Modifier.weight(1f)) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentPadding = PaddingValues(bottom = 4.dp),
                ) {
                    // System/Input/Error messages
                    items(uiState.systemLines, key = { it.id }) { line ->
                        SystemLineItem(line = line, fontSize = uiState.fontSize)
                    }
                    // VT100 emulator output
                    items(uiState.ptyLines.size) { idx ->
                        PtyLineItem(text = uiState.ptyLines[idx], fontSize = uiState.fontSize)
                    }
                }
            }

            if (!uiState.autoScroll) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (totalLines > 0) listState.animateScrollToItem(totalLines - 1)
                        }
                        viewModel.toggleAutoScroll()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .size(40.dp),
                    containerColor = TerminalGreen.copy(alpha = 0.9f),
                    contentColor = TerminalBg,
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                }
            }
        }

        QuickCommandBar(
            onCtrlC = { viewModel.sendCtrlC() },
            onCtrlD = { viewModel.sendCtrlD() },
            onTab = { viewModel.sendTab() },
            onUp = { viewModel.historyUp() },
            onDown = { viewModel.historyDown() },
        )

        InputBar(
            value = uiState.input,
            onValueChange = { viewModel.updateInput(it) },
            onSend = { if (uiState.input.isNotBlank()) viewModel.sendInput(uiState.input) },
            onCopy = {
                val sys = uiState.systemLines.joinToString("\n") { it.content }
                val pty = uiState.ptyLines.joinToString("\n") { it.text }
                clipboard.setText(AnnotatedString("$sys\n$pty"))
            },
            enabled = uiState.connectionState is WsState.Connected,
        )
    }

    if (showSessions) {
        SessionsDialog(
            sessions = uiState.sessions,
            currentId = uiState.currentSessionId,
            onSwitch = { viewModel.switchSession(it); showSessions = false },
            onKill = { viewModel.killSession(it) },
            onNew = { viewModel.newSession(); showSessions = false },
            onDismiss = { showSessions = false },
        )
    }

    if (showMenu) {
        TerminalMenuDialog(
            onClear = { viewModel.clearOutput(); showMenu = false },
            onDisconnect = { viewModel.disconnect(); showMenu = false },
            onDismiss = { showMenu = false },
        )
    }
}

@Composable
private fun StatusBar(
    state: WsState,
    sessionId: String,
    sessionCount: Int,
    onMenuClick: () -> Unit,
    onSessionsClick: () -> Unit,
) {
    val (color, label) = when (state) {
        is WsState.Connected    -> TerminalGreen to "Connected"
        is WsState.Connecting   -> TerminalYellow to "Connecting..."
        is WsState.Reconnecting -> TerminalYellow to "Reconnecting (${state.attempt}/8)"
        is WsState.Error        -> TerminalRed to "Error"
        is WsState.Disconnected -> TerminalDim to "Disconnected"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).background(color, shape = MaterialTheme.shapes.small))
        Spacer(Modifier.width(6.dp))
        Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = color)
        if (sessionId.isNotBlank()) {
            Spacer(Modifier.width(8.dp))
            Text("·", color = TerminalDim, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "session:${sessionId.take(8)}",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TerminalDim,
                modifier = Modifier.clickable(onClick = onSessionsClick).padding(horizontal = 4.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (sessionCount > 1) {
            TextButton(onClick = onSessionsClick, contentPadding = PaddingValues(horizontal = 4.dp)) {
                Text("$sessionCount sessions", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TerminalBlue)
            }
        }
        IconButton(onClick = onMenuClick, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = TerminalDim)
        }
    }
}

@Composable
private fun SystemLineItem(line: TerminalLine, fontSize: Int) {
    val color = when (line.type) {
        LineType.INPUT  -> TerminalBlue
        LineType.SYSTEM -> TerminalGreen
        LineType.ERROR  -> TerminalRed
    }
    Text(
        text = line.content,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            color = color,
            lineHeight = (fontSize * 1.5).sp,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PtyLineItem(text: AnnotatedString, fontSize: Int) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            color = Color(204, 204, 204),
            lineHeight = (fontSize * 1.5).sp,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun QuickCommandBar(
    onCtrlC: () -> Unit, onCtrlD: () -> Unit, onTab: () -> Unit,
    onUp: () -> Unit, onDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("Ctrl+C" to onCtrlC, "Ctrl+D" to onCtrlD, "Tab" to onTab, "↑" to onUp, "↓" to onDown)
            .forEach { (label, action) ->
                OutlinedButton(
                    onClick = action,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    border = BorderStroke(1.dp, TerminalBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TerminalText),
                ) { Text(label, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
            }
    }
}

@Composable
private fun InputBar(
    value: String, onValueChange: (String) -> Unit,
    onSend: () -> Unit, onCopy: () -> Unit, enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TerminalSurface)
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(">", fontFamily = FontFamily.Monospace, color = TerminalGreen, fontSize = 16.sp)
        TextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.weight(1f), enabled = enabled, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = TerminalBg, unfocusedContainerColor = TerminalBg,
                focusedIndicatorColor = TerminalGreen, unfocusedIndicatorColor = TerminalBorder,
                focusedTextColor = TerminalText, unfocusedTextColor = TerminalText,
                cursorColor = TerminalGreen, disabledContainerColor = TerminalBg,
                disabledIndicatorColor = TerminalBorder,
            ),
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            placeholder = { Text("type command...", color = TerminalDim, fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
        )
        IconButton(onClick = onCopy, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy output", tint = TerminalDim)
        }
        IconButton(
            onClick = onSend, enabled = enabled && value.isNotBlank(),
            modifier = Modifier.size(36.dp),
        ) {
            Icon(Icons.Default.ArrowForward, contentDescription = "Send",
                tint = if (enabled && value.isNotBlank()) TerminalGreen else TerminalBorder)
        }
    }
}

@Composable
private fun SessionsDialog(
    sessions: List<SessionInfo>, currentId: String,
    onSwitch: (String) -> Unit, onKill: (String) -> Unit,
    onNew: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TerminalSurface,
        title = { Text("Sessions", fontFamily = FontFamily.Monospace, color = TerminalText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                sessions.forEach { session ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (session.id == currentId) TerminalBg else TerminalSurface)
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(8.dp).background(if (session.alive) TerminalGreen else TerminalRed))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = session.id.take(12) + if (session.id == currentId) " (active)" else "",
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TerminalText,
                            modifier = Modifier.weight(1f).clickable { onSwitch(session.id) },
                        )
                        IconButton(onClick = { onKill(session.id) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Kill", tint = TerminalRed, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) {
                Icon(Icons.Default.Add, contentDescription = null, tint = TerminalGreen)
                Spacer(Modifier.width(4.dp))
                Text("New Session", fontFamily = FontFamily.Monospace, color = TerminalGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", fontFamily = FontFamily.Monospace, color = TerminalDim)
            }
        },
    )
}

@Composable
private fun TerminalMenuDialog(
    onClear: () -> Unit, onDisconnect: () -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TerminalSurface,
        title = { Text("Menu", fontFamily = FontFamily.Monospace, color = TerminalText) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Clear output", fontFamily = FontFamily.Monospace, color = TerminalText) },
                    leadingContent = { Icon(Icons.Default.ClearAll, contentDescription = null, tint = TerminalBlue) },
                    modifier = Modifier.clickable(onClick = onClear),
                    colors = ListItemDefaults.colors(containerColor = TerminalSurface),
                )
                ListItem(
                    headlineContent = { Text("Disconnect", fontFamily = FontFamily.Monospace, color = TerminalRed) },
                    leadingContent = { Icon(Icons.Default.LinkOff, contentDescription = null, tint = TerminalRed) },
                    modifier = Modifier.clickable(onClick = onDisconnect),
                    colors = ListItemDefaults.colors(containerColor = TerminalSurface),
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontFamily = FontFamily.Monospace, color = TerminalDim)
            }
        },
    )
}