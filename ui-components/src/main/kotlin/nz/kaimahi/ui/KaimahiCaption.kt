package nz.kaimahi.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Section / drawer / chip caption — mono caps, muted, letter-spaced.
 * Used everywhere a small mono uppercase label appears (drawer
 * "PROJECTS" / "TOOLS", About section headers, chat tool-stripe). One
 * implementation, one set of dial knobs.
 */
@Composable
fun KaimahiCaption(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 10.5f.sp,
    letterSpacing: TextUnit = 1.5.sp,
    weight: FontWeight = FontWeight.Medium,
    color: androidx.compose.ui.graphics.Color? = null,
) {
    val tokens = LocalKaimahiColors.current
    Text(
        text = text,
        modifier = modifier,
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize,
        letterSpacing = letterSpacing,
        fontWeight = weight,
        color = color ?: tokens.muted,
    )
}
