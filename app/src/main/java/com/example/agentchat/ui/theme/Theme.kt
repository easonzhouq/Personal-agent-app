package com.example.agentchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = IosBlueDark,
    onPrimary = Color.Black,
    secondary = IosIndigo,
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFF2C2C2E),
    onBackground = Color(0xFFF2F2F7),
    onSurface = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFFAEAEB2),
    outline = Color(0xFF545458),
    error = IosRed,
)

private val LightColorScheme = lightColorScheme(
    primary = IosBlue,
    onPrimary = Color.White,
    secondary = IosIndigo,
    background = IosBackground,
    surface = IosSurface,
    surfaceVariant = IosSecondaryFill,
    onBackground = IosLabel,
    onSurface = IosLabel,
    onSurfaceVariant = IosSecondaryLabel,
    outline = IosSeparator,
    error = IosRed,
)

@Composable
fun AgentChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
