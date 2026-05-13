package nz.kaimahi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Kaimahi brand mark — a clean geometric "K" with a strong horizontal
 * bar that reads as a worker's tool (level / spirit-line) and as a
 * horizon. Drawn entirely in Compose Canvas; no raster assets.
 *
 * Three render modes:
 *   - `Style.Brand`     — gradient stroke for hero / login.
 *   - `Style.Solid`     — single-color, picks up `tint` (default = primary).
 *   - `Style.Outline`   — same shape, hairline weight for chrome / chips.
 *
 * The geometry is normalized to a 24-unit square then scaled by `size`,
 * so the mark looks identical at 16dp or 128dp.
 */
@Composable
fun KaimahiLogo(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    style: KaimahiLogoStyle = KaimahiLogoStyle.Brand,
    tint: Color? = null,
) {
    val tokens = LocalKaimahiColors.current
    val brand = tokens.brand
    val signal = tokens.signal
    val brush: Brush = when (style) {
        KaimahiLogoStyle.Brand -> tokens.brandGradient
        KaimahiLogoStyle.Solid -> androidx.compose.ui.graphics.SolidColor(tint ?: brand)
        KaimahiLogoStyle.Outline -> androidx.compose.ui.graphics.SolidColor(tint ?: brand)
    }
    val strokeFraction = when (style) {
        KaimahiLogoStyle.Brand -> 0.16f
        KaimahiLogoStyle.Solid -> 0.16f
        KaimahiLogoStyle.Outline -> 0.06f
    }
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w.coerceAtMost(h)
        val pad = s * 0.10f
        val sw = s * strokeFraction
        val left = pad
        val right = s - pad
        val top = pad
        val bottom = s - pad
        val mid = (top + bottom) / 2f
        val barX = left + (right - left) * 0.55f

        // Vertical stroke of K
        drawPath(
            path = Path().apply {
                moveTo(left, top)
                lineTo(left, bottom)
            },
            brush = brush,
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Upper diagonal: from vertical mid out to upper-right
        drawPath(
            path = Path().apply {
                moveTo(left, mid)
                lineTo(right, top)
            },
            brush = brush,
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Lower diagonal: from vertical mid out to lower-right
        drawPath(
            path = Path().apply {
                moveTo(left, mid)
                lineTo(right, bottom)
            },
            brush = brush,
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
        // Horizontal bar through the join — the "worker's level"
        drawPath(
            path = Path().apply {
                moveTo(barX, mid)
                lineTo(right + s * 0.04f, mid)
            },
            brush = brush,
            style = Stroke(width = sw * 0.9f, cap = StrokeCap.Round),
        )
        // Subtle dot above the bar — kowhai signal accent (only in Brand mode)
        if (style == KaimahiLogoStyle.Brand) {
            drawCircle(
                color = signal,
                radius = sw * 0.45f,
                center = Offset(right + s * 0.02f, mid - s * 0.18f),
            )
        }
    }
}

enum class KaimahiLogoStyle {
    Brand,
    Solid,
    Outline,
}
