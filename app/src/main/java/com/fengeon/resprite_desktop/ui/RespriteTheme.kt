package com.fengeon.resprite_desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Extra semantic color tokens that Material's ColorScheme doesn't cover.
 * Every chrome color used by the editor UI lives here, so the UI never
 * hardcodes a Color(0x...) again (see design/editor-redesign.html, step 1).
 */
data class RespriteColors(
    val topBar: Color,
    val panel: Color,
    val panelAlt: Color,
    val panelDeep: Color,
    val divider: Color,
    val toolActiveBg: Color,
    val toolIcon: Color,
    val toolIconActive: Color,
    val selectedRow: Color,
    val frameIdle: Color,
    val frameSelectedBg: Color,
    val checkerLight: Color,
    val checkerDark: Color,
    val statusText: Color,
    val swatchBorder: Color,
    val swatchBorderActive: Color,
)

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

private val DarkResprite = RespriteColors(
    topBar = Color(0xFF1E1E1E),
    panel = Color(0xFF282828),
    panelAlt = Color(0xFF1A1A1A),
    panelDeep = Color(0xFF202020),
    divider = Color(0xFF333333),
    toolActiveBg = Color(0xFF4FC3F7),
    toolIcon = Color(0xFFCCCCCC),
    toolIconActive = Color(0xFF000000),
    selectedRow = Color(0xFF333333),
    frameIdle = Color(0xFF3A3A3A),
    frameSelectedBg = Color(0xFF4FC3F7),
    checkerLight = Color(0xFFFFFFFF),
    checkerDark = Color(0xFFCCCCCC),
    statusText = Color(0xFF80CBC4),
    swatchBorder = Color(0xFF505050),
    swatchBorderActive = Color(0xFFFFFFFF),
)

val LocalRespriteColors = staticCompositionLocalOf { DarkResprite }

/** Shorthand accessor for chrome colors inside composables: `rsColors.topBar` */
val rsColors: RespriteColors
    @Composable get() = LocalRespriteColors.current

@Composable
fun RespriteTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRespriteColors provides DarkResprite) {
        MaterialTheme(
            colorScheme = DarkColors,
            content = content
        )
    }
}
