package com.shadowself.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary         = Color(0xFF7C4DFF),
    onPrimary       = Color.White,
    background      = Color(0xFF0D0D0D),
    surface         = Color(0xFF1A1A1A),
    onSurface       = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFF9E9E9E),
    error           = Color(0xFFEF5350)
)

private val LightColors = lightColorScheme(
    primary         = Color(0xFF512DA8),
    onPrimary       = Color.White,
    background      = Color(0xFFFAFAFA),
    surface         = Color.White,
    onSurface       = Color(0xFF212121),
    error           = Color(0xFFF44336)
)

@Composable
fun ShadowSelfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content     = content
    )
}
