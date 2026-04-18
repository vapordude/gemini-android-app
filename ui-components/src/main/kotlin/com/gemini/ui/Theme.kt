package com.gemini.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GeminiBlue = Color(0xFF1A73E8)
private val GeminiBlueDark = Color(0xFF8AB4F8)
private val GeminiSurface = Color(0xFFF8F9FA)
private val GeminiSurfaceDark = Color(0xFF202124)
private val GeminiOnSurface = Color(0xFF1F1F1F)
private val GeminiOnSurfaceDark = Color(0xFFE8EAED)
private val GeminiSecondary = Color(0xFFE8F0FE)
private val GeminiSecondaryDark = Color(0xFF2A3B57)

private val LightColors = lightColorScheme(
    primary = GeminiBlue,
    onPrimary = Color.White,
    primaryContainer = GeminiSecondary,
    onPrimaryContainer = GeminiOnSurface,
    secondary = GeminiBlue,
    secondaryContainer = GeminiSecondary,
    onSecondaryContainer = GeminiOnSurface,
    background = GeminiSurface,
    onBackground = GeminiOnSurface,
    surface = Color.White,
    onSurface = GeminiOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = GeminiBlueDark,
    onPrimary = Color(0xFF0B1A2A),
    primaryContainer = GeminiSecondaryDark,
    onPrimaryContainer = GeminiOnSurfaceDark,
    secondary = GeminiBlueDark,
    secondaryContainer = GeminiSecondaryDark,
    onSecondaryContainer = GeminiOnSurfaceDark,
    background = GeminiSurfaceDark,
    onBackground = GeminiOnSurfaceDark,
    surface = Color(0xFF2B2B2F),
    onSurface = GeminiOnSurfaceDark,
)

@Composable
fun GeminiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
