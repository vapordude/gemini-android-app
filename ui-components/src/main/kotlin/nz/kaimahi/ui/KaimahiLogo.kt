package nz.kaimahi.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kaimahi brand mark — the Cathedral koru, a logarithmic spiral
 * `r(θ) = a · e^(b·θ)` rendered procedurally in Compose Canvas. The
 * shape sits in the Cathedral AI / Scarlet Sovereign Systems brand
 * family, with siblings Crimson and Scarlet using the same koru
 * silhouette.
 *
 * Three render modes:
 *   - `Style.Brand`     — gradient stroke for hero / login / splash.
 *   - `Style.Solid`     — single-color, picks up `tint` (default = primary).
 *   - `Style.Outline`   — same shape at hairline weight for chrome / chips.
 *
 * The geometry is normalised to a unit square then scaled by `size`, so
 * the mark looks identical at 16dp or 128dp.
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
    val brush: Brush = when (style) {
        KaimahiLogoStyle.Brand -> tokens.brandGradient
        KaimahiLogoStyle.Solid -> SolidColor(tint ?: brand)
        KaimahiLogoStyle.Outline -> SolidColor(tint ?: brand)
    }
    val strokeFraction = when (style) {
        KaimahiLogoStyle.Brand -> 0.14f
        KaimahiLogoStyle.Solid -> 0.14f
        KaimahiLogoStyle.Outline -> 0.05f
    }
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w.coerceAtMost(h)
        val sw = s * strokeFraction
        val cx = w / 2f
        val cy = h / 2f

        // Logarithmic spiral r = a · e^(b · θ). Parameters match the
        // locked design system (docs/design/marks.jsx): a=0.4, b=0.235,
        // 2.7 turns sampled at 90 points per turn. Spiral grows
        // outward; the inner end is the centre of the koru, the outer
        // hook is the open end. Normalised to a 24-unit viewbox then
        // scaled by `s`.
        val viewboxHalf = 12f
        val scale = s / (viewboxHalf * 2f)
        val a = 0.4
        val b = 0.235
        val turns = 2.7
        val samplesPerTurn = 90
        val total = (turns * samplesPerTurn).toInt()

        val spiral = Path()
        for (i in 0..total) {
            val theta = (i.toDouble() / samplesPerTurn) * Math.PI * 2.0
            val r = a * Math.exp(b * theta)
            val px = (cos(theta) * r).toFloat()
            val py = (sin(theta) * r).toFloat()
            val x = cx + px * scale
            val y = cy + py * scale
            if (i == 0) spiral.moveTo(x, y) else spiral.lineTo(x, y)
        }
        drawPath(
            path = spiral,
            brush = brush,
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )
    }
}

enum class KaimahiLogoStyle {
    Brand,
    Solid,
    Outline,
}

/**
 * Kaimahi splash mark — an angular geometric spiral derived from the
 * Scarlet training math-shape. Eight kite-shaped petals rotating round
 * a centre eye, with kōura radial circuit-traces fanning out at the
 * petal mid-lines. Geometry matches the locked design system
 * (`docs/design/marks.jsx::MathSpiral`).
 *
 * Use for splash screens, About hero, "loading the model" states —
 * places where the mark is the focus, not chrome.
 */
@Composable
fun KaimahiMathSpiral(
    modifier: Modifier = Modifier,
    size: Dp = 128.dp,
    tint: Color? = null,
) {
    val tokens = LocalKaimahiColors.current
    val ink = tint ?: tokens.brand
    val trace = tokens.signal
    val eye = Color(0xFF0A0A0A)
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w.coerceAtMost(h)
        val cx = w / 2f
        val cy = h / 2f

        // Normalise to a 28-unit viewbox so the same kite vertices from
        // marks.jsx render at any size.
        val viewboxHalf = 14f
        val scale = s / (viewboxHalf * 2f)

        // Eight kites at 45° steps. Vertex template in local axis
        // (along +x outward): [0,0], [3.4,-2], [10,0], [3.4,2].
        val template = arrayOf(
            floatArrayOf(0.0f, 0.0f),
            floatArrayOf(3.4f, -2.0f),
            floatArrayOf(10.0f, 0.0f),
            floatArrayOf(3.4f, 2.0f),
        )
        for (i in 0 until 8) {
            val angle = (i * 45.0 * Math.PI / 180.0).toFloat()
            val ca = cos(angle)
            val sa = sin(angle)
            val petal = Path()
            for (j in template.indices) {
                val (lx, ly) = template[j].let { it[0] to it[1] }
                val rx = lx * ca - ly * sa
                val ry = lx * sa + ly * ca
                val x = cx + rx * scale
                val y = cy + ry * scale
                if (j == 0) petal.moveTo(x, y) else petal.lineTo(x, y)
            }
            petal.close()
            drawPath(petal, brush = SolidColor(ink))
        }

        // Kōura radial circuit-traces fanning out at the petal mid-lines
        // (offset 22.5° from each petal). Short tick from r=4.5 to r=11.5
        // plus a small terminal pip at r=12. Sized to look like PCB tracks.
        val strokePx = (s * 0.014f).coerceAtLeast(1f)
        for (i in 0 until 8) {
            val angle = ((i * 45 + 22.5) * Math.PI / 180.0).toFloat()
            val ca = cos(angle)
            val sa = sin(angle)
            val x1 = cx + (ca * 4.5f) * scale
            val y1 = cy + (sa * 4.5f) * scale
            val x2 = cx + (ca * 11.5f) * scale
            val y2 = cy + (sa * 11.5f) * scale
            drawLine(
                color = trace,
                start = Offset(x1, y1),
                end = Offset(x2, y2),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
            // Terminal pip.
            val px = cx + (ca * 12.0f) * scale
            val py = cy + (sa * 12.0f) * scale
            drawCircle(
                color = trace,
                radius = (s * 0.02f).coerceAtLeast(1f),
                center = Offset(px, py),
            )
        }

        // Centre eye — black puck on top of kōura disc.
        drawCircle(
            color = eye,
            radius = 1.6f * scale,
            center = Offset(cx, cy),
        )
        drawCircle(
            color = trace,
            radius = 1.1f * scale,
            center = Offset(cx, cy),
        )
    }
}

/**
 * Scarlet Sovereign Systems heraldic sigil — a kōura ring with a whero
 * diamond gem at the centre, a vertical kōura bar bisecting the field,
 * and four corner dots. Geometry matches the locked design system
 * (`docs/design/marks.jsx::SLGWWSigil`).
 *
 * Use sparingly as a corner watermark on sovereign-tier surfaces —
 * About screen header, deployments, B2B docs. Most chrome uses
 * `KaimahiLogo` (the koru) instead. Pass an opacity via the standard
 * `Modifier.alpha(...)` if you want it whispered.
 */
@Composable
fun KaimahiSigil(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val tokens = LocalKaimahiColors.current
    val koura = tokens.signal
    val whero = tokens.brand
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w.coerceAtMost(h)
        val cx = w / 2f
        val cy = h / 2f

        // Viewbox-normalised so geometry constants from marks.jsx
        // (radii 10 / 8.4, corner-dot at 6.4, vertical bar 15 tall)
        // translate directly. Source viewbox is -12..12 (24 units).
        val viewboxHalf = 12f
        val scale = s / (viewboxHalf * 2f)

        // Outer ring at r=10.
        drawCircle(
            color = koura,
            radius = 10f * scale,
            center = Offset(cx, cy),
            style = Stroke(width = 0.6f * scale),
        )
        // Inner ring at r=8.4 (slightly faded).
        drawCircle(
            color = koura.copy(alpha = 0.6f),
            radius = 8.4f * scale,
            center = Offset(cx, cy),
            style = Stroke(width = 0.3f * scale),
        )
        // Vertical bar — 1.4 wide × 15 tall, centred.
        val barHalfW = 0.7f * scale
        val barHalfH = 7.5f * scale
        drawRect(
            color = koura,
            topLeft = Offset(cx - barHalfW, cy - barHalfH),
            size = androidx.compose.ui.geometry.Size(barHalfW * 2f, barHalfH * 2f),
        )
        // Diamond gem at centre: whero fill, kōura hairline outline.
        val gemPath = Path().apply {
            moveTo(cx, cy - 2.6f * scale)
            lineTo(cx + 2.4f * scale, cy)
            lineTo(cx, cy + 2.6f * scale)
            lineTo(cx - 2.4f * scale, cy)
            close()
        }
        drawPath(gemPath, brush = SolidColor(whero))
        drawPath(
            gemPath,
            brush = SolidColor(koura),
            style = Stroke(width = 0.35f * scale),
        )
        // Four corner dots at (±6.4, ±6.4).
        val dotR = 0.7f * scale
        listOf(
            Offset(cx + 6.4f * scale, cy - 6.4f * scale),
            Offset(cx + 6.4f * scale, cy + 6.4f * scale),
            Offset(cx - 6.4f * scale, cy + 6.4f * scale),
            Offset(cx - 6.4f * scale, cy - 6.4f * scale),
        ).forEach { drawCircle(color = koura, radius = dotR, center = it) }
    }
}
