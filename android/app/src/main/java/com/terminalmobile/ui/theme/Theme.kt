package com.terminalmobile.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TerminalGreen = Color(0xFF4EC9B0)
val TerminalBlue = Color(0xFF4FC1FF)
val TerminalYellow = Color(0xFFDCDCAA)
val TerminalRed = Color(0xFFF48771)
val TerminalBg = Color(0xFF0D1117)
val TerminalSurface = Color(0xFF161B22)
val TerminalBorder = Color(0xFF30363D)
val TerminalText = Color(0xFFD4D4D4)
val TerminalDim = Color(0xFF6E7681)

private val darkColorScheme = darkColorScheme(
    primary = TerminalGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1F3226),
    secondary = TerminalBlue,
    onSecondary = Color.Black,
    background = TerminalBg,
    onBackground = TerminalText,
    surface = TerminalSurface,
    onSurface = TerminalText,
    surfaceVariant = Color(0xFF21262D),
    onSurfaceVariant = TerminalDim,
    outline = TerminalBorder,
    error = TerminalRed,
    onError = Color.Black,
)

@Composable
fun TerminalMobileTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme,
        typography = Typography(),
        content = content,
    )
}
