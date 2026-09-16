package ru.ymlstudio

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF236C50), secondary = Color(0xFF236C50), tertiary = Color(0xFF845400),
    onPrimary = Color.White, onSecondary = Color.White, surfaceTint = Color(0xFF236C50),
    background = Color(0xFFF5F8F6), surface = Color.White,
    primaryContainer = Color(0xFFE7F1EB), onPrimaryContainer = Color(0xFF163E2E),
    onBackground = Color(0xFF19221D), onSurface = Color(0xFF19221D),
    onSurfaceVariant = Color(0xFF52655A), outlineVariant = Color(0xFFD6E1D9))
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD5AE), secondary = Color(0xFF8BD5AE), tertiary = Color(0xFFFFCF7A),
    onPrimary = Color(0xFF103824), onSecondary = Color(0xFF103824), surfaceTint = Color(0xFF8BD5AE),
    background = Color(0xFF111814), surface = Color(0xFF1B241E),
    primaryContainer = Color(0xFF284D39), onPrimaryContainer = Color(0xFFC4F1D7),
    onBackground = Color(0xFFE2EAE4), onSurface = Color(0xFFE2EAE4),
    onSurfaceVariant = Color(0xFFB3C4B8), outlineVariant = Color(0xFF415348))

@Composable internal fun StudioTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
    }
}
