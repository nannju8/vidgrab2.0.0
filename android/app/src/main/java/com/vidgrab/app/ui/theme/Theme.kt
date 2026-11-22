package com.vidgrab.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Palette « Obsidian & Neon Aurora ». */
object VG {
    val Indigo = Color(0xFF6366F1)
    val Cyan = Color(0xFF06B6D4)
    val Coral = Color(0xFFF43F5E)
    val Magenta = Color(0xFFEC4899)
    val Gold = Color(0xFFFBBF24)

    val BgTop = Color(0xFF0B0D14)
    val BgBottom = Color(0xFF121624)

    val brand = Brush.linearGradient(listOf(Indigo, Cyan))
    val alert = Brush.linearGradient(listOf(Coral, Magenta))
    val progressBrush = Brush.horizontalGradient(listOf(Cyan, Indigo))
}

val LocalDarkTheme = staticCompositionLocalOf { true }

private val Dark = darkColorScheme(
    primary = VG.Indigo, secondary = VG.Cyan, tertiary = VG.Magenta,
    background = VG.BgTop, surface = Color(0xFF171B2B), onBackground = Color(0xFFE8EAF6), onSurface = Color(0xFFE8EAF6),
    surfaceVariant = Color(0xFF1E2336), onSurfaceVariant = Color(0xFFA6ADC8), error = VG.Coral
)

private val Light = lightColorScheme(
    primary = VG.Indigo, secondary = VG.Cyan, tertiary = VG.Magenta,
    background = Color(0xFFF4F6FF), surface = Color(0xFFFFFFFF), onBackground = Color(0xFF12152A), onSurface = Color(0xFF12152A),
    surfaceVariant = Color(0xFFE6E9F7), onSurfaceVariant = Color(0xFF50567A), error = VG.Coral
)

@Composable
fun VidGrabTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
    }
}
