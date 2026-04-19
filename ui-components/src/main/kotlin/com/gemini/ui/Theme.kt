package com.gemini.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// AI Studio reference palette (see styles.md): dark-first, low-chroma surfaces
// with thin strokes, topped with a blue→violet→magenta accent.
val AiStudioBlue = Color(0xFF1F6FEB)
val AiStudioBlueLight = Color(0xFF8AB4FF)
val AiStudioViolet = Color(0xFF7B61FF)
val AiStudioMagenta = Color(0xFFE0307E)

private val AisBg = Color(0xFF191919)
private val AisPanel = Color(0xFF232323)
private val AisLine = Color(0xFF2A2A2A)
private val AisLineStrong = Color(0xFF3E3E3E)
private val AisText = Color(0xFFD4D4D4)
private val AisMuted = Color(0xFF8C8C8C)
private val AisChip = Color(0xFF323232)

// Light mirror using similar hierarchy.
private val AisBgLight = Color(0xFFFAFAFA)
private val AisPanelLight = Color(0xFFFFFFFF)
private val AisLineLight = Color(0xFFEAEAEA)
private val AisLineStrongLight = Color(0xFFD0D0D0)
private val AisTextLight = Color(0xFF141414)
private val AisMutedLight = Color(0xFF6B6B6B)
private val AisChipLight = Color(0xFFEFEFEF)

val GeminiGradient = Brush.linearGradient(
    listOf(AiStudioBlue, AiStudioViolet, AiStudioMagenta)
)

private val DarkColors = darkColorScheme(
    primary = AiStudioBlueLight,
    onPrimary = Color(0xFF0A1530),
    primaryContainer = Color(0xFF1B2A4D),
    onPrimaryContainer = Color(0xFFD7E2FF),
    secondary = AiStudioBlueLight,
    onSecondary = Color(0xFF0A1530),
    secondaryContainer = AisChip,
    onSecondaryContainer = AisText,
    tertiary = AiStudioMagenta,
    onTertiary = Color.White,
    background = AisBg,
    onBackground = AisText,
    surface = AisPanel,
    onSurface = AisText,
    surfaceVariant = AisLine,
    onSurfaceVariant = AisMuted,
    outline = AisLineStrong,
    outlineVariant = AisLine,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF4A1217),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = AiStudioBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3ECFF),
    onPrimaryContainer = Color(0xFF0B1F4A),
    secondary = AiStudioBlue,
    onSecondary = Color.White,
    secondaryContainer = AisChipLight,
    onSecondaryContainer = AisTextLight,
    tertiary = AiStudioMagenta,
    onTertiary = Color.White,
    background = AisBgLight,
    onBackground = AisTextLight,
    surface = AisPanelLight,
    onSurface = AisTextLight,
    surfaceVariant = AisLineLight,
    onSurfaceVariant = AisMutedLight,
    outline = AisLineStrongLight,
    outlineVariant = AisLineLight,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCE8E6),
    onErrorContainer = Color(0xFF410E0B),
)

// Radii from styles.md: xl 32, lg 16, md 12, sm 9.
private val GeminiShapes = Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val GeminiTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 44.sp,
        letterSpacing = (-1.6).sp,
        lineHeight = 46.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        letterSpacing = (-1.0).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
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
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
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
        shapes = GeminiShapes,
        content = content,
    )
}
