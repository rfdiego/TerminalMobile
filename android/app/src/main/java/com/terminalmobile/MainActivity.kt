package com.terminalmobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import com.terminalmobile.ui.screens.ConnectScreen
import com.terminalmobile.ui.screens.SettingsScreen
import com.terminalmobile.ui.screens.TerminalScreen
import com.terminalmobile.ui.theme.TerminalBg
import com.terminalmobile.ui.theme.TerminalMobileTheme
import com.terminalmobile.viewmodel.TerminalViewModel

sealed class Screen {
    object Connect : Screen()
    object Terminal : Screen()
    object Settings : Screen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TerminalMobileTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(TerminalBg)
                        .systemBarsPadding(),
                    color = TerminalBg,
                ) {
                    TerminalApp()
                }
            }
        }
    }
}

@Composable
fun TerminalApp() {
    val viewModel: TerminalViewModel = viewModel()
    var screen by remember { mutableStateOf<Screen>(Screen.Connect) }

    when (screen) {
        is Screen.Connect -> ConnectScreen(
            viewModel = viewModel,
            onConnected = { screen = Screen.Terminal },
        )
        is Screen.Terminal -> TerminalScreen(
            viewModel = viewModel,
            onDisconnect = { screen = Screen.Connect },
        )
        is Screen.Settings -> SettingsScreen(
            viewModel = viewModel,
            onBack = { screen = Screen.Terminal },
        )
    }
}
