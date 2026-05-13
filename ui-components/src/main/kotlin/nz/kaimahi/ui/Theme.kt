package nz.kaimahi.ui

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

// ─── Kaimahi tokens ────────────────────────────────────────────────────
// Mirror of docs/STYLES.md and docs/BRAND.md. Keep all three in sync.
//
// Design language: dark-first, grounded, sovereign. Three material
// references — pounamu (greenstone), kowhai (native flower), kauri
// (timber). No Google blue. Brand gradient is reserved for hero +
// learning moments; UI chrome uses neutral grades only.

// Dark surfaces (deep, slightly teal-tinted neutrals — workshop at night).
private val Bg         = Color(0xFF0E1416)
private val Surface1   = Color(0xFF1A2226)
private val Surface2   = Color(0xFF232E33)
private val Surface3   = Color(0xFF2C383D)

// Lines.
private val Line       = Color(0xFF243035)
private val LineStrong = Color(0xFF34434A)

// Text.
private val TextStrong = Color(0xFFF2F5F5)
private val Text       = Color(0xFFDDE5E6)
private val TextSec    = Color(0xFFA8B5B7)
private val TextMuted  = Color(0xFF6F7D80)
private val TextDis    = Color(0xFF4A5559)

// Chip / interactive surface.
private val Chip       = Color(0xFF1F2A2E)

// Brand — pounamu (greenstone), the interactive primary.
private val Pounamu        = Color(0xFF4FA3A3)
private val PounamuOn      = Color(0xFF042525)
private val PounamuMuted   = Color(0xFF1A4747)
private val PounamuMutedOn = Color(0xFFB8E3E3)

// Secondary — kowhai (yellow flower), signal / learning.
private val Kowhai         = Color(0xFFE8C75F)
private val KowhaiOn       = Color(0xFF2B2407)
private val KowhaiMuted    = Color(0xFF4A3E1A)
private val KowhaiMutedOn  = Color(0xFFF4E1A2)

// Tertiary — kauri (warm timber), agent-acting accent.
private val Kauri          = Color(0xFFBD7B5C)
private val KauriOn        = Color(0xFF301607)
private val KauriMuted     = Color(0xFF4A2D1C)
private val KauriMutedOn   = Color(0xFFE7C2AC)

// Semantic states.
private val StateSuccess   = Color(0xFF7BC18D) // ngahere — forest
private val StateWarn      = Color(0xFFE0B25B) // muted kowhai
private val StateDanger    = Color(0xFFE07A6B) // warm coral, not pure red

/**
 * Kaimahi brand gradient. Pounamu → ngahere → kowhai → kauri. Used for
 * the hero, the streaming/loading indicator, and the memory-folded
 * "learning" halo. NEVER as UI chrome.
 */
val KaimahiBrandGradient: Brush = Brush.linearGradient(
    0.00f to Pounamu,
    0.40f to StateSuccess,
    0.75f to Kowhai,
    1.00f to Kauri,
)

/** Ambient "workshop at night" glow — used behind hero + empty states. */
val KaimahiDuskGradient: Brush = Brush.radialGradient(
    0.0f to Pounamu.copy(alpha = 0.18f),
    0.5f to Kauri.copy(alpha = 0.06f),
    1.0f to Color.Transparent,
)

/** Soft halo behind agentic-memory affordances ("I learned this"). */
val KaimahiLearningHalo: Brush = Brush.radialGradient(
    0.0f to Kowhai.copy(alpha = 0.22f),
    0.6f to Kowhai.copy(alpha = 0.06f),
    1.0f to Color.Transparent,
)

// Light mirror — pounamu darkened for contrast, neutrals inverted.
private val BgLight         = Color(0xFFFAFBFB)
private val Surface1Light   = Color(0xFFF1F4F4)
private val Surface2Light   = Color(0xFFE6EBEB)
private val Surface3Light   = Color(0xFFD8E0E0)
private val LineLight       = Color(0xFFE0E5E5)
private val LineStrongLight = Color(0xFFC9D2D2)
private val TextStrongLight = Color(0xFF0A1213)
private val TextLight       = Color(0xFF1F2A2B)
private val TextSecLight    = Color(0xFF3D4A4B)
private val TextMutedLight  = Color(0xFF6B7878)
private val ChipLight       = Color(0xFFEAF0F0)
private val PounamuLight    = Color(0xFF2E7878)

private val DarkColors = darkColorScheme(
    primary              = Pounamu,
    onPrimary            = PounamuOn,
    primaryContainer     = PounamuMuted,
    onPrimaryContainer   = PounamuMutedOn,
    secondary            = Kowhai,
    onSecondary          = KowhaiOn,
    secondaryContainer   = KowhaiMuted,
    onSecondaryContainer = KowhaiMutedOn,
    tertiary             = Kauri,
    onTertiary           = KauriOn,
    tertiaryContainer    = KauriMuted,
    onTertiaryContainer  = KauriMutedOn,
    background           = Bg,
    onBackground         = Text,
    surface              = Surface1,
    onSurface            = Text,
    surfaceVariant       = Surface2,
    onSurfaceVariant     = TextSec,
    surfaceTint          = Pounamu,
    inverseSurface       = Text,
    inverseOnSurface     = Bg,
    outline              = LineStrong,
    outlineVariant       = Line,
    scrim                = Color.Black,
    error                = StateDanger,
    onError              = Color(0xFF3B0F0A),
    errorContainer       = Color(0xFF4F1B14),
    onErrorContainer     = Color(0xFFFFD9D2),
)

private val LightColors = lightColorScheme(
    primary              = PounamuLight,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFCBE8E8),
    onPrimaryContainer   = Color(0xFF052525),
    secondary            = Color(0xFF8A6A0F),
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFFAEAB2),
    onSecondaryContainer = Color(0xFF2B2407),
    tertiary             = Color(0xFF8A4D2E),
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFF6D7C5),
    onTertiaryContainer  = Color(0xFF3A1B07),
    background           = BgLight,
    onBackground         = TextLight,
    surface              = Surface1Light,
    onSurface            = TextLight,
    surfaceVariant       = Surface2Light,
    onSurfaceVariant     = TextSecLight,
    surfaceTint          = PounamuLight,
    outline              = LineStrongLight,
    outlineVariant       = LineLight,
    error                = Color(0xFFB4271C),
    onError              = Color.White,
    errorContainer       = Color(0xFFFCD9D2),
    onErrorContainer     = Color(0xFF420B07),
)

// Radii — pill (28) for primary CTAs + chat composer, 16 for cards.
private val KaimahiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// Typography — system sans for now. Slight negative letter-spacing on
// headlines for craft / settled feel; positive tracking on labels for
// clarity at small sizes.
private val KaimahiTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 48.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.6).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = (-1.2).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.15).sp,
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
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
)

/** Extra tokens Material 3 doesn't expose — surfaced via CompositionLocal. */
data class KaimahiExtendedColors(
    val surfaceMax: Color,
    val textStrong: Color,
    val muted: Color,
    val disabled: Color,
    val success: Color,
    val warn: Color,
    val danger: Color,
    /** Pounamu — the brand primary, exposed for direct draw (e.g. logo). */
    val brand: Color,
    /** Kowhai — signal accent for learning + memory affordances. */
    val signal: Color,
    /** Kauri — agent-acting accent. */
    val act: Color,
    /** Linear gradient: hero, streaming, full-bleed onboarding moments. */
    val brandGradient: Brush,
    /** Radial glow behind hero + empty states. */
    val duskGradient: Brush,
    /** Kowhai halo behind "I learned / I remember" affordances. */
    val learningHalo: Brush,
)

private val DarkExtended = KaimahiExtendedColors(
    surfaceMax = Surface3,
    textStrong = TextStrong,
    muted = TextMuted,
    disabled = TextDis,
    success = StateSuccess,
    warn = StateWarn,
    danger = StateDanger,
    brand = Pounamu,
    signal = Kowhai,
    act = Kauri,
    brandGradient = KaimahiBrandGradient,
    duskGradient = KaimahiDuskGradient,
    learningHalo = KaimahiLearningHalo,
)

private val LightExtended = KaimahiExtendedColors(
    surfaceMax = Surface3Light,
    textStrong = TextStrongLight,
    muted = TextMutedLight,
    disabled = Color(0xFF9AA8A8),
    success = Color(0xFF2E7D43),
    warn = Color(0xFF8A6A0F),
    danger = Color(0xFFB4271C),
    brand = PounamuLight,
    signal = Color(0xFF8A6A0F),
    act = Color(0xFF8A4D2E),
    brandGradient = KaimahiBrandGradient,
    duskGradient = KaimahiDuskGradient,
    learningHalo = KaimahiLearningHalo,
)

val LocalKaimahiColors = staticCompositionLocalOf { DarkExtended }

object KaimahiTokens {
    val colors: KaimahiExtendedColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKaimahiColors.current
}

@Composable
fun KaimahiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val extended = if (darkTheme) DarkExtended else LightExtended
    CompositionLocalProvider(LocalKaimahiColors provides extended) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = KaimahiTypography,
            shapes = KaimahiShapes,
            content = content,
        )
    }
}
