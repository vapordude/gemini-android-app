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

// ─── Kaimahi tokens — Cathedral palette ────────────────────────────────
// Mirror of docs/STYLES.md and docs/BRAND.md. Keep all three in sync.
//
// Design language: dark-first, sovereign, mathematical. Three references —
// whero (crimson), kōura (gold), pō (night). Kaimahi sits in the
// Cathedral AI / Scarlet Sovereign Systems family; the palette is shared
// across Crimson, Scarlet, Lux, and the Pātaka Whero / Takurua Whero Iho
// sibling sites. The legacy pounamu / kowhai / kauri palette was retired
// when Kaimahi joined the family.

// Dark surfaces (deep, neutral-warm blacks — cathedral at night).
private val Bg         = Color(0xFF0A0A0A)
private val Surface1   = Color(0xFF111111)
private val Surface2   = Color(0xFF161616)
private val Surface3   = Color(0xFF1E1E1E)

// Lines.
private val Line       = Color(0xFF222222)
private val LineStrong = Color(0xFF2F2F2F)

// Text.
private val TextStrong = Color(0xFFF4ECE4)
private val Text       = Color(0xFFE0E0E0)
private val TextSec    = Color(0xFFB0ADA6)
private val TextMuted  = Color(0xFF7A7770)
private val TextDis    = Color(0xFF484440)

// Chip / interactive surface.
private val Chip       = Color(0xFF1A1A1A)

// Brand — whero (crimson), the interactive primary.
private val Whero          = Color(0xFFC1272D)
private val WheroOn        = Color(0xFFFFFFFF)
private val WheroMuted     = Color(0xFF3A1212)
private val WheroMutedOn   = Color(0xFFF4ECE4)

// Secondary — kōura (gold), signal / learning.
private val Koura          = Color(0xFFD8A857)
private val KouraOn        = Color(0xFF2B2107)
private val KouraMuted     = Color(0xFF3A2D14)
private val KouraMutedOn   = Color(0xFFF4DFA8)

// Tertiary — ember (warm orange between whero and kōura), agent-acting accent.
private val Ember          = Color(0xFFD97742)
private val EmberOn        = Color(0xFF2F1107)
private val EmberMuted     = Color(0xFF3F1E10)
private val EmberMutedOn   = Color(0xFFF3CBAF)

// Semantic states.
private val StateSuccess   = Color(0xFF6B8E5E) // kauri-green, used sparingly
private val StateWarn      = Color(0xFFD8A857) // kōura
private val StateDanger    = Color(0xFFC1272D) // whero proper

/**
 * Kaimahi brand gradient. Whero → ember → kōura. Used for the hero, the
 * streaming indicator, and the memory-folded "learning" halo. NEVER as
 * UI chrome.
 */
val KaimahiBrandGradient: Brush = Brush.linearGradient(
    0.00f to Whero,
    0.55f to Ember,
    1.00f to Koura,
)

/** Ambient "cathedral at night" radial glow — used behind hero + empty states. */
val KaimahiDuskGradient: Brush = Brush.radialGradient(
    0.0f to Whero.copy(alpha = 0.15f),
    0.5f to Ember.copy(alpha = 0.05f),
    1.0f to Color.Transparent,
)

/** Soft halo behind agentic-memory affordances ("I learned this"). */
val KaimahiLearningHalo: Brush = Brush.radialGradient(
    0.0f to Koura.copy(alpha = 0.22f),
    0.6f to Koura.copy(alpha = 0.06f),
    1.0f to Color.Transparent,
)

// Light mirror — whero darkened for contrast, neutrals inverted.
private val BgLight         = Color(0xFFFAF7F2)
private val Surface1Light   = Color(0xFFF3EFE8)
private val Surface2Light   = Color(0xFFE9E4DC)
private val Surface3Light   = Color(0xFFDCD6CC)
private val LineLight       = Color(0xFFD6D0C5)
private val LineStrongLight = Color(0xFFBDB6A8)
private val TextStrongLight = Color(0xFF18160F)
private val TextLight       = Color(0xFF1F1C14)
private val TextSecLight    = Color(0xFF4B463B)
private val TextMutedLight  = Color(0xFF6E6960)
private val ChipLight       = Color(0xFFEEE8DC)
private val WheroLight      = Color(0xFF8F2520)

private val DarkColors = darkColorScheme(
    primary              = Whero,
    onPrimary            = WheroOn,
    primaryContainer     = WheroMuted,
    onPrimaryContainer   = WheroMutedOn,
    secondary            = Koura,
    onSecondary          = KouraOn,
    secondaryContainer   = KouraMuted,
    onSecondaryContainer = KouraMutedOn,
    tertiary             = Ember,
    onTertiary           = EmberOn,
    tertiaryContainer    = EmberMuted,
    onTertiaryContainer  = EmberMutedOn,
    background           = Bg,
    onBackground         = Text,
    surface              = Surface1,
    onSurface            = Text,
    surfaceVariant       = Surface2,
    onSurfaceVariant     = TextSec,
    surfaceTint          = Whero,
    inverseSurface       = Text,
    inverseOnSurface     = Bg,
    outline              = LineStrong,
    outlineVariant       = Line,
    scrim                = Color.Black,
    error                = StateDanger,
    onError              = Color.White,
    errorContainer       = WheroMuted,
    onErrorContainer     = WheroMutedOn,
)

private val LightColors = lightColorScheme(
    primary              = WheroLight,
    onPrimary            = Color.White,
    primaryContainer     = Color(0xFFF5E0DE),
    onPrimaryContainer   = Color(0xFF3A0F0C),
    secondary            = Color(0xFF8A6A0F),
    onSecondary          = Color.White,
    secondaryContainer   = Color(0xFFFAEAB2),
    onSecondaryContainer = Color(0xFF2B2107),
    tertiary             = Color(0xFFA84D20),
    onTertiary           = Color.White,
    tertiaryContainer    = Color(0xFFF6D7C5),
    onTertiaryContainer  = Color(0xFF3F1107),
    background           = BgLight,
    onBackground         = TextLight,
    surface              = Surface1Light,
    onSurface            = TextLight,
    surfaceVariant       = Surface2Light,
    onSurfaceVariant     = TextSecLight,
    surfaceTint          = WheroLight,
    outline              = LineStrongLight,
    outlineVariant       = LineLight,
    error                = WheroLight,
    onError              = Color.White,
    errorContainer       = Color(0xFFF5E0DE),
    onErrorContainer     = Color(0xFF3A0F0C),
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
    /** Whero (crimson) — the brand primary, exposed for direct draw (e.g. logo). */
    val brand: Color,
    /** Kōura (gold) — signal accent for learning + memory affordances. */
    val signal: Color,
    /** Ember — agent-acting accent, between whero and kōura. */
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
    brand = Whero,
    signal = Koura,
    act = Ember,
    brandGradient = KaimahiBrandGradient,
    duskGradient = KaimahiDuskGradient,
    learningHalo = KaimahiLearningHalo,
)

private val LightExtended = KaimahiExtendedColors(
    surfaceMax = Surface3Light,
    textStrong = TextStrongLight,
    muted = TextMutedLight,
    disabled = Color(0xFFB0ADA6),
    success = Color(0xFF496B3D),
    warn = Color(0xFF8A6A0F),
    danger = WheroLight,
    brand = WheroLight,
    signal = Color(0xFF8A6A0F),
    act = Color(0xFFA84D20),
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

/**
 * Theme variants beyond the standard dark/light pair. Mirrors the
 * design system in `docs/design/kaimahi.css`:
 *
 *   - [Cathedral]   — the default dark on neutral blacks (#0A0A0A …).
 *   - [PatakaWarm]  — warm paper for cosy / fireside scenes.
 *   - [PoStealth]   — true OLED black, dim foreground; nightstand
 *                     reading without burning eyes.
 *
 * Selecting a variant always wins over `darkTheme` (the variant is
 * the more specific signal).
 */
enum class KaimahiThemeVariant {
    /** Default. Black + crimson + gold on neutral blacks. */
    Cathedral,
    /** Warm paper. Whero darkened to read on cream; kōura antique. */
    PatakaWarm,
    /** OLED black. Dim foreground, no full white. Nightstand reading. */
    PoStealth,
}

// ── Stealth variant tokens — OLED black, dim foreground ────────────────
private val StealthBg          = Color(0xFF000000)
private val StealthSurface1    = Color(0xFF050505)
private val StealthSurface2    = Color(0xFF0A0A0A)
private val StealthSurface3    = Color(0xFF101010)
private val StealthLine        = Color(0xFF141414)
private val StealthLineStrong  = Color(0xFF1C1C1C)
private val StealthTextStrong  = Color(0xFFB0A8A0)
private val StealthText        = Color(0xFF807872)
private val StealthTextSec     = Color(0xFF5A554F)
private val StealthTextMuted   = Color(0xFF3C3833)
private val StealthTextDis     = Color(0xFF232020)
// Brand stays whero/kōura/ember; only the surfaces + text shift.

private val StealthColors = darkColorScheme(
    primary              = Whero,
    onPrimary            = WheroOn,
    primaryContainer     = WheroMuted,
    onPrimaryContainer   = WheroMutedOn,
    secondary            = Koura,
    onSecondary          = KouraOn,
    secondaryContainer   = KouraMuted,
    onSecondaryContainer = KouraMutedOn,
    tertiary             = Ember,
    onTertiary           = EmberOn,
    tertiaryContainer    = EmberMuted,
    onTertiaryContainer  = EmberMutedOn,
    background           = StealthBg,
    onBackground         = StealthText,
    surface              = StealthSurface1,
    onSurface            = StealthText,
    surfaceVariant       = StealthSurface2,
    onSurfaceVariant     = StealthTextSec,
    surfaceTint          = Whero,
    inverseSurface       = StealthText,
    inverseOnSurface     = StealthBg,
    outline              = StealthLineStrong,
    outlineVariant       = StealthLine,
    scrim                = Color.Black,
    error                = StateDanger,
    onError              = Color.White,
    errorContainer       = WheroMuted,
    onErrorContainer     = WheroMutedOn,
)

private val StealthExtended = KaimahiExtendedColors(
    surfaceMax = StealthSurface3,
    textStrong = StealthTextStrong,
    muted = StealthTextMuted,
    disabled = StealthTextDis,
    success = StateSuccess,
    warn = StateWarn,
    danger = StateDanger,
    brand = Whero,
    signal = Koura,
    act = Ember,
    brandGradient = KaimahiBrandGradient,
    duskGradient = KaimahiDuskGradient,
    learningHalo = KaimahiLearningHalo,
)

@Composable
fun KaimahiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    variant: KaimahiThemeVariant = KaimahiThemeVariant.Cathedral,
    content: @Composable () -> Unit,
) {
    val (scheme, extended) = when (variant) {
        KaimahiThemeVariant.PoStealth -> StealthColors to StealthExtended
        KaimahiThemeVariant.PatakaWarm -> LightColors to LightExtended
        KaimahiThemeVariant.Cathedral -> {
            if (darkTheme) DarkColors to DarkExtended
            else LightColors to LightExtended
        }
    }
    CompositionLocalProvider(LocalKaimahiColors provides extended) {
        MaterialTheme(
            colorScheme = scheme,
            typography = KaimahiTypography,
            shapes = KaimahiShapes,
            content = content,
        )
    }
}
