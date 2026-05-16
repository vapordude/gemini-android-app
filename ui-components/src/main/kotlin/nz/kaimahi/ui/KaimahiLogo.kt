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

        // Logarithmic spiral. The koru is r = a * e^(b * θ); we sweep θ
        // from outside in towards the centre. Constants tuned so the
        // outer hook lands roughly at the bounding circle and the inner
        // turn closes neatly.
        val a = s * 0.42f
        val b = -0.21f  // negative -> inward as θ grows
        val thetaStart = 0.0
        val thetaEnd = Math.PI * 3.6  // ~1.8 turns
        val samples = 220
        val rotation = Math.PI * 0.55  // tilts the opening to the upper-right

        val spiral = Path()
        var moved = false
        var t = thetaStart
        val step = (thetaEnd - thetaStart) / samples
        while (t <= thetaEnd) {
            val r = a * Math.exp(b * t)
            val angle = t + rotation
            val x = (cx + r * cos(angle)).toFloat()
            val y = (cy + r * sin(angle)).toFloat()
            if (!moved) {
                spiral.moveTo(x, y)
                moved = true
            } else {
                spiral.lineTo(x, y)
            }
            t += step
        }
        drawPath(
            path = spiral,
            brush = brush,
            style = Stroke(width = sw, cap = StrokeCap.Round),
        )

        // Inner dot — the koru's centre point, the seed of the unfurl.
        val innerR = a * Math.exp(b * thetaEnd).toFloat()
        val innerAngle = (thetaEnd + rotation).toFloat()
        drawCircle(
            color = when (style) {
                KaimahiLogoStyle.Brand -> brand
                else -> tint ?: brand
            },
            radius = (sw * 0.35f).coerceAtLeast(1f),
            center = Offset(
                cx + innerR * cos(innerAngle),
                cy + innerR * sin(innerAngle),
            ),
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
 * Scarlet training math-shape. Eight petal segments rotated through a
 * power series, rendered in crimson with circuit-trace decorations.
 * Use this for splash screens, About hero, "loading the model" states —
 * places where the mark is the focus, not chrome.
 */
@Composable
fun KaimahiMathSpiral(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    tint: Color? = null,
) {
    val tokens = LocalKaimahiColors.current
    val ink = tint ?: tokens.brand
    val trace = tokens.signal
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w.coerceAtMost(h)
        val cx = w / 2f
        val cy = h / 2f
        val petals = 8
        val outerR = s * 0.42f
        val innerR = s * 0.16f
        val rotationStep = (Math.PI * 2 / petals).toFloat()
        val petalSpread = rotationStep * 0.82f
        val twist = rotationStep * 0.35f

        for (i in 0 until petals) {
            val baseAngle = i * rotationStep - Math.PI.toFloat() / 2f
            val a0 = baseAngle - petalSpread / 2f
            val a1 = baseAngle + petalSpread / 2f
            val ax = cx + outerR * cos(a0)
            val ay = cy + outerR * sin(a0)
            val bx = cx + outerR * cos(a1)
            val by = cy + outerR * sin(a1)
            val cxi = cx + innerR * cos(a1 + twist)
            val cyi = cy + innerR * sin(a1 + twist)
            val dxi = cx + innerR * cos(a0 + twist)
            val dyi = cy + innerR * sin(a0 + twist)
            val petal = Path().apply {
                moveTo(ax, ay)
                lineTo(bx, by)
                lineTo(cxi, cyi)
                lineTo(dxi, dyi)
                close()
            }
            drawPath(
                path = petal,
                brush = SolidColor(ink),
            )
        }

        // Circuit-trace decoration: small connecting lines from petal
        // outer-corners off to the edge, evoking the math-shape's
        // PCB-like fanout. Drawn in kōura signal at low alpha.
        val edgeR = s * 0.48f
        for (i in 0 until petals) {
            val angle = i * rotationStep - Math.PI.toFloat() / 2f + rotationStep / 2f
            val fromX = cx + outerR * cos(angle)
            val fromY = cy + outerR * sin(angle)
            val toX = cx + edgeR * cos(angle)
            val toY = cy + edgeR * sin(angle)
            drawLine(
                color = trace.copy(alpha = 0.5f),
                start = Offset(fromX, fromY),
                end = Offset(toX, toY),
                strokeWidth = s * 0.012f,
            )
        }
    }
}

/**
 * Scarlet Sovereign Systems heraldic sigil — a circular mark with the
 * `S/L/G/W/W` letters arranged around a central vertical with a gem.
 * Use sparingly as a corner watermark on "sovereign-tier" surfaces
 * (settings → about, deployment screens, B2B-flavoured docs). Most
 * Kaimahi chrome uses `KaimahiLogo` (the koru) instead.
 */
@Composable
fun KaimahiSigil(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color? = null,
) {
    val tokens = LocalKaimahiColors.current
    val ink = tint ?: tokens.brand
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = w.coerceAtMost(h)
        val cx = w / 2f
        val cy = h / 2f
        val ring = s * 0.42f
        val stroke = (s * 0.025f).coerceAtLeast(1f)

        // Outer ring.
        drawCircle(
            color = ink,
            radius = ring,
            center = Offset(cx, cy),
            style = Stroke(width = stroke),
        )
        // Inner ring (concentric).
        drawCircle(
            color = ink.copy(alpha = 0.4f),
            radius = ring * 0.75f,
            center = Offset(cx, cy),
            style = Stroke(width = stroke * 0.7f),
        )

        // Vertical bar through centre — the "spine".
        drawLine(
            color = ink,
            start = Offset(cx, cy - ring * 0.92f),
            end = Offset(cx, cy + ring * 0.92f),
            strokeWidth = stroke * 1.1f,
        )

        // Diamond gem at the centre.
        val gem = s * 0.06f
        val gemPath = Path().apply {
            moveTo(cx, cy - gem)
            lineTo(cx + gem, cy)
            lineTo(cx, cy + gem)
            lineTo(cx - gem, cy)
            close()
        }
        drawPath(gemPath, brush = SolidColor(ink))

        // Four cardinal dots on the outer ring at NE/NW/SE/SW.
        val dotR = s * 0.022f
        val diag = (Math.PI / 4).toFloat()
        listOf(
            Offset(cx + ring * cos(diag), cy - ring * sin(diag)),
            Offset(cx - ring * cos(diag), cy - ring * sin(diag)),
            Offset(cx + ring * cos(diag), cy + ring * sin(diag)),
            Offset(cx - ring * cos(diag), cy + ring * sin(diag)),
        ).forEach { drawCircle(color = ink, radius = dotR, center = it) }
    }
}
