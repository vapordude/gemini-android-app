package com.gemini.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- AI Studio / Gemini 2025 tokens --------------------------------------
// One-for-one mirror of styles.md. Keep the two in sync when either moves.

// Dark surfaces (stacked, no tint shift).
private val AisBg         = Color(0xFF131314)
private val AisSurface    = Color(0xFF1E1F20)
private val AisSurfaceHi  = Color(0xFF282A2C)
private val AisSurfaceMax = Color(0xFF35373A)

// Lines.
private val AisLine       = Color(0xFF2F3133)
private val AisLineStrong = Color(0xFF3C3D3F)

// Text.
private val AisText       = Color(0xFFE3E3E3)
private val AisTextStrong = Color(0xFFF5F5F5)
private val AisSecondary  = Color(0xFFC4C7C5)
private val AisMuted      = Color(0xFF9AA0A6)
private val AisDisabled   = Color(0xFF5F6368)

// Interactive / chips.
private val AisChip       = Color(0xFF2A2B2F)

// Accent (Google Blue 300 family).
private val AccentBlue    = Color(0xFF8AB4F8)
private val AccentBlueOn  = Color(0xFF062E6F)
private val AccentMuted   = Color(0xFF1A3A73) // container bg on dark
private val AccentMutedOn = Color(0xFFD3E3FD)

// Secondary / tertiary accents (used sparingly).
private val AccentTeal    = Color(0xFF78D9EC)
private val AccentViolet  = Color(0xFFC58AF9)

// Semantic states (Google 300 on dark).
private val StateSuccess  = Color(0xFF81C995)
private val StateWarn     = Color(0xFFFDD663)
private val StateDanger   = Color(0xFFF28B82)

// Gemini four-color spark — 2025 brand refresh.
val GeminiSparkBlue   = Color(0xFF4285F4)
val GeminiSparkRed    = Color(0xFFEA4335)
val GeminiSparkYellow = Color(0xFFFBBC05)
val GeminiSparkGreen  = Color(0xFF34A853)

/**
 * Official Gemini gradient. Use only for the spark/hero, streaming
 * progress bar, and onboarding halo — never for regular UI chrome.
 */
val GeminiSparkGradient: Brush = Brush.linearGradient(
    0.00f to GeminiSparkBlue,
    0.33f to GeminiSparkGreen,
    0.66f to GeminiSparkYellow,
    1.00f to GeminiSparkRed,
)

/** Ambient dusk glow used behind the login hero and the empty chat state. */
val GeminiDuskGradient: Brush = Brush.radialGradient(
    0.0f to AccentBlue.copy(alpha = 0.18f),
    0.5f to AccentViolet.copy(alpha = 0.10f),
    1.0f to Color.Transparent,
)

// Light mirror (same roles, inverted luminance).
private val AisBgLight         = Color(0xFFFFFFFF)
private val AisSurfaceLight    = Color(0xFFF8F9FA)
private val AisSurfaceHiLight  = Color(0xFFF1F3F4)
private val AisSurfaceMaxLight = Color(0xFFE8EAED)
private val AisLineLight       = Color(0xFFE8EAED)
private val AisLineStrongLight = Color(0xFFDADCE0)
private val AisTextLight       = Color(0xFF1F1F1F)
private val AisTextStrongLight = Color(0xFF0B0B0B)
private val AisSecondaryLight  = Color(0xFF444746)
private val AisMutedLight      = Color(0xFF5F6368)
private val AccentBlueLight    = Color(0xFF0B57D0)
private val AccentMutedLight   = Color(0xFFD3E3FD)
private val AccentMutedOnLight = Color(0xFF041E49)
private val AisChipLight       = Color(0xFFF1F3F4)

private val DarkColors = darkColorScheme(
    primary              = AccentBlue,
    onPrimary            = AccentBlueOn,
    primaryContainer     = AccentMuted,
    onPrimaryContainer   = AccentMutedOn,
    secondary            = AccentTeal,
    onSecondary          = Color(0xFF003544),
    secondaryContainer   = AisChip,
    onSecondaryContainer = AisText,
    tertiary             = AccentViolet,
    onTertiary           = Color(0xFF2C1255),
    tertiaryContainer    = Color(0xFF3A1B6B),
    onTertiaryContainer  = Color(0xFFEADDFF),
    background           = AisBg,
    onBackground         = AisText,
    surface              = AisSurface,
    onSurface            = AisText,
    surfaceVariant       = AisSurfaceHi,
    onSurfaceVariant     = AisSecondary,
    surfaceTint          = AccentBlue,
    inverseSurface       = AisText,
    inverseOnSurface     = AisBg,
    outline              = AisLineStrong,
    outlineVariant       = AisLine,
    scrim                = Color.Black,
    error                = StateDanger,
    onError              = Color(0xFF601410),
    errorContainer       = Color(0xFF4A1217),
    onErrorContainer     = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary              = AccentBlueLight,
    onPrimary            = Color.White,
    primaryContainer     = AccentMutedLight,
    onPrimaryContainer   = AccentMutedOnLight,
    secondary            = Color(0xFF0057B7),
    onSecondary          = Color.White,
    secondaryContainer   = AisChipLight,
    onSecondaryContainer = AisTextLight,
    tertiary             = Color(0xFF6C4DB8),
    onTertiary           = Color.White,
    background           = AisBgLight,
    onBackground         = AisTextLight,
    surface              = AisSurfaceLight,
    onSurface            = AisTextLight,
    surfaceVariant       = AisSurfaceHiLight,
    onSurfaceVariant     = AisSecondaryLight,
    surfaceTint          = AccentBlueLight,
    outline              = AisLineStrongLight,
    outlineVariant       = AisLineLight,
    error                = Color(0xFFB3261E),
    onError              = Color.White,
    errorContainer       = Color(0xFFFCE8E6),
    onErrorContainer     = Color(0xFF410E0B),
)

// Radii — see styles.md §2. 2xl (28dp) is the pill used for chat input,
// primary CTAs and large sheets. `extraLarge` on M3 lines up with that role.
private val GeminiShapes = Shapes(
    extraSmall  = RoundedCornerShape(6.dp),   // dense chips
    small       = RoundedCornerShape(12.dp),  // chips, list rows
    medium      = RoundedCornerShape(16.dp),  // cards, dropdowns
    large       = RoundedCornerShape(20.dp),  // dialogs, sheets
    extraLarge  = RoundedCornerShape(28.dp),  // pill buttons, chat input
)

// Typography — falls back to the system sans until we ship Google Sans
// Flex/Text as bundled assets. Weights and tracking match AI Studio's CSS.
private val GeminiTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 48.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1.9).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1.4).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
)

/** Extra tokens Material 3 doesn't expose — exposed via CompositionLocal. */
data class GeminiExtendedColors(
    val surfaceMax: Color,
    val textStrong: Color,
    val muted: Color,
    val disabled: Color,
    val success: Color,
    val warn: Color,
    val danger: Color,
    val sparkGradient: Brush,
    val duskGradient: Brush,
)

private val DarkExtended = GeminiExtendedColors(
    surfaceMax = AisSurfaceMax,
    textStrong = AisTextStrong,
    muted = AisMuted,
    disabled = AisDisabled,
    success = StateSuccess,
    warn = StateWarn,
    danger = StateDanger,
    sparkGradient = GeminiSparkGradient,
    duskGradient = GeminiDuskGradient,
)

private val LightExtended = GeminiExtendedColors(
    surfaceMax = AisSurfaceMaxLight,
    textStrong = AisTextStrongLight,
    muted = AisMutedLight,
    disabled = Color(0xFF9AA0A6),
    success = Color(0xFF1E8E3E),
    warn = Color(0xFFB06000),
    danger = Color(0xFFC5221F),
    sparkGradient = GeminiSparkGradient,
    duskGradient = GeminiDuskGradient,
)

val LocalGeminiColors = staticCompositionLocalOf { DarkExtended }

object GeminiTokens {
    val colors: GeminiExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalGeminiColors.current
}

@Composable
fun GeminiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extended = if (darkTheme) DarkExtended else LightExtended
    CompositionLocalProvider(LocalGeminiColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = GeminiTypography,
            shapes = GeminiShapes,
            content = content,
        )
    }
}
