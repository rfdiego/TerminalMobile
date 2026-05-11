package com.terminalmobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminalmobile.data.ConnectionConfig
import com.terminalmobile.data.PreferencesRepository
import com.terminalmobile.ui.theme.*
import com.terminalmobile.viewmodel.TerminalViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: TerminalViewModel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val config by viewModel.prefs.configFlow.collectAsState(initial = ConnectionConfig())
    var fontSize by remember(config.fontSize) { mutableIntStateOf(config.fontSize) }
    var autoConnect by remember(config.autoConnect) { mutableStateOf(config.autoConnect) }

    Scaffold(
        containerColor = TerminalBg,
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontFamily = FontFamily.Monospace, color = TerminalText) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = TerminalText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = TerminalSurface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = TerminalSurface),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Terminal", fontFamily = FontFamily.Monospace, color = TerminalDim, fontSize = 12.sp)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Font Size: ${fontSize}sp", fontFamily = FontFamily.Monospace, color = TerminalText, modifier = Modifier.weight(1f))
                    }
                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { fontSize = it.toInt() },
                        onValueChangeFinished = {
                            scope.launch { viewModel.prefs.save(config.copy(fontSize = fontSize)) }
                        },
                        valueRange = 10f..20f,
                        steps = 9,
                        colors = SliderDefaults.colors(
                            thumbColor = TerminalGreen,
                            activeTrackColor = TerminalGreen,
                            inactiveTrackColor = TerminalBorder,
                        ),
                    )

                    Text(
                        "Preview: claude --help",
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize.sp,
                        color = TerminalText,
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = TerminalSurface),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Connection", fontFamily = FontFamily.Monospace, color = TerminalDim, fontSize = 12.sp)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Auto-connect on start", fontFamily = FontFamily.Monospace, color = TerminalText, modifier = Modifier.weight(1f))
                        Switch(
                            checked = autoConnect,
                            onCheckedChange = {
                                autoConnect = it
                                scope.launch { viewModel.prefs.save(config.copy(autoConnect = it)) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TerminalGreen,
                                checkedTrackColor = TerminalGreen.copy(alpha = 0.3f),
                            ),
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = TerminalSurface),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("About", fontFamily = FontFamily.Monospace, color = TerminalDim, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("TerminalMobile v1.0", fontFamily = FontFamily.Monospace, color = TerminalText)
                    Text("Remote Claude Code Terminal", fontFamily = FontFamily.Monospace, color = TerminalDim, fontSize = 12.sp)
                }
            }
        }
    }
}
