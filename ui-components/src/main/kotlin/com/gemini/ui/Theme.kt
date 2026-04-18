package com.gemini.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// AI Studio-inspired palette: deep blue → violet → magenta on dark, vivid blue on light.
val AiStudioBlue = Color(0xFF1F6FEB)
val AiStudioBlueLight = Color(0xFF8AB4FF)
val AiStudioViolet = Color(0xFF7B61FF)
val AiStudioMagenta = Color(0xFFE0307E)
private val AiSurface = Color(0xFFFAFAFC)
private val AiSurfaceDark = Color(0xFF0F1115)
private val AiSurfaceVariant = Color(0xFFEFEFF5)
private val AiSurfaceVariantDark = Color(0xFF1A1D24)
private val AiOnSurface = Color(0xFF111317)
private val AiOnSurfaceDark = Color(0xFFE8EAF0)
private val AiOutline = Color(0xFFD7D9E0)
private val AiOutlineDark = Color(0xFF2A2E37)

val GeminiGradient = Brush.linearGradient(
    listOf(AiStudioBlue, AiStudioViolet, AiStudioMagenta)
)

private val LightColors = lightColorScheme(
    primary = AiStudioBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFF),
    onPrimaryContainer = Color(0xFF0B1F4A),
    secondary = AiStudioViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFEAFF),
    onSecondaryContainer = Color(0xFF1F0E54),
    tertiary = AiStudioMagenta,
    onTertiary = Color.White,
    background = AiSurface,
    onBackground = AiOnSurface,
    surface = Color.White,
    onSurface = AiOnSurface,
    surfaceVariant = AiSurfaceVariant,
    onSurfaceVariant = Color(0xFF505462),
    outline = AiOutline,
    outlineVariant = Color(0xFFE5E7EE),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = AiStudioBlueLight,
    onPrimary = Color(0xFF0A1530),
    primaryContainer = Color(0xFF1B2A4D),
    onPrimaryContainer = Color(0xFFD7E2FF),
    secondary = AiStudioViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2A2154),
    onSecondaryContainer = Color(0xFFE8E1FF),
    tertiary = AiStudioMagenta,
    onTertiary = Color.White,
    background = AiSurfaceDark,
    onBackground = AiOnSurfaceDark,
    surface = Color(0xFF13161B),
    onSurface = AiOnSurfaceDark,
    surfaceVariant = AiSurfaceVariantDark,
    onSurfaceVariant = Color(0xFFB3B6C0),
    outline = AiOutlineDark,
    outlineVariant = Color(0xFF22262E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val GeminiTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
)

@Composable
fun GeminiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = GeminiTypography,
        content = content,
    )
}
