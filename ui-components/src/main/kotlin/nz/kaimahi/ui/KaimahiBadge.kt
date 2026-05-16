package nz.kaimahi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Small status pill used to signal which inference path the agent is
 * currently driving, how persistent memory is doing, and whether a
 * vendor accelerator is engaged. Three kinds:
 *
 * | Kind | Visual | Meaning |
 * | --- | --- | --- |
 * | Cloud  | whero fill   | cloud Gemini in use this turn |
 * | Local  | ember fill   | on-device LM in use this turn |
 * | Dual   | gradient     | both authenticated; policy decides per call |
 * | Memory | kōura dot    | persistent-memory recall folded into this turn |
 * | Delegate | brand outline | vendor delegate (NPU/GPU) engaged |
 *
 * Designed to disappear into the chat top bar — small, low-luminance,
 * legible at a glance. Tap targets are 32dp min so they're usable as
 * affordances (open the runtime info sheet).
 */
@Composable
fun KaimahiBadge(
    kind: BadgeKind,
    label: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKaimahiColors.current
    val (fill, content) = when (kind) {
        BadgeKind.Cloud    -> tokens.brand.copy(alpha = 0.18f) to tokens.brand
        BadgeKind.Local    -> tokens.act.copy(alpha = 0.20f) to tokens.act
        BadgeKind.Dual     -> Color.Transparent to tokens.textStrong
        BadgeKind.Memory   -> tokens.signal.copy(alpha = 0.18f) to tokens.signal
        BadgeKind.Delegate -> Color.Transparent to tokens.brand
    }
    val border = when (kind) {
        BadgeKind.Dual     -> tokens.brand.copy(alpha = 0.6f)
        BadgeKind.Delegate -> tokens.brand
        else               -> Color.Transparent
    }
    Row(
        modifier = modifier
            .background(fill, RoundedCornerShape(50))
            .border(1.dp, border, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BadgeDot(kind, tokens)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

@Composable
private fun BadgeDot(kind: BadgeKind, tokens: KaimahiExtendedColors) {
    when (kind) {
        BadgeKind.Cloud -> Box(
            Modifier
                .size(6.dp)
                .background(tokens.brand, CircleShape)
        )
        BadgeKind.Local -> Box(
            Modifier
                .size(6.dp)
                .background(tokens.act, CircleShape)
        )
        BadgeKind.Dual -> Box(
            Modifier
                .size(8.dp)
                .background(tokens.brandGradient, CircleShape)
        )
        BadgeKind.Memory -> Box(
            Modifier
                .size(6.dp)
                .background(tokens.signal, CircleShape)
        )
        BadgeKind.Delegate -> Box(
            Modifier
                .size(6.dp)
                .background(tokens.brandGradient, CircleShape)
        )
    }
}

enum class BadgeKind {
    Cloud,
    Local,
    Dual,
    Memory,
    Delegate,
}
