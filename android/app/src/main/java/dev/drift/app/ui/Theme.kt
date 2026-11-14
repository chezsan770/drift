package dev.drift.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// One quiet accent; everything else is neutral grey.
private val Accent = Color(0xFF8AB4F8)

private val Amoled = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFFE6E6E6),
    surface = Color.Black,
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFF8E8E8E),
    surfaceContainer = Color(0xFF111111),
    outlineVariant = Color(0xFF1E1E1E),
    error = Color(0xFFF28B82),
)

private val Light = lightColorScheme(
    primary = Color(0xFF1A73E8),
    background = Color.White,
    surface = Color.White,
    onSurfaceVariant = Color(0xFF6B6B6B),
    outlineVariant = Color(0xFFEAEAEA),
)

@Composable
fun DriftTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Amoled else Light, content = content)
}
