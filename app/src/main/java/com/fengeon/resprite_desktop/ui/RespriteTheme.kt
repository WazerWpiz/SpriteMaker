package com.fengeon.resprite_desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF001F2A),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF202020),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF2D2D2D),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF383838),
    onSurfaceVariant = Color(0xFFC9C9C9),
    error = Color(0xFFEF5350)
)

@Composable
fun RespriteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
