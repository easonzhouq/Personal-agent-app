package com.example.agentchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
private val DarkColorScheme = darkColorScheme(
    primary = NightSkyPrimary,
    onPrimary = NightSkyOnPrimary,
    secondary = NightSkySecondary,
    onSecondary = NightSkyOnSecondary,
    background = NightSkyBackground,
    surface = NightSkySurface,
    surfaceVariant = NightSkySurfaceVariant,
    onBackground = NightSkyOnBackground,
    onSurface = NightSkyOnSurface,
    onSurfaceVariant = NightSkyOnSurfaceVariant,
    outline = NightSkyOutline,
    error = NightSkyError,
    onError = NightSkyOnError,
)

private val LightColorScheme = lightColorScheme(
    primary = SkyCreamPrimary,
    onPrimary = SkyCreamOnPrimary,
    secondary = SkyCreamSecondary,
    onSecondary = SkyCreamOnSecondary,
    background = SkyCreamBackground,
    surface = SkyCreamSurface,
    surfaceVariant = SkyCreamSurfaceVariant,
    onBackground = SkyCreamOnBackground,
    onSurface = SkyCreamOnSurface,
    onSurfaceVariant = SkyCreamOnSurfaceVariant,
    outline = SkyCreamOutline,
    error = SkyCreamError,
    onError = SkyCreamOnError,
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
