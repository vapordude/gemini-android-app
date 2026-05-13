package nz.kaimahi.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private const val MARKER = "@@DIFF@@"

fun extractDiff(output: String): Pair<String, String?> {
    val idx = output.indexOf(MARKER)
    if (idx < 0) return output to null
    val head = output.substring(0, idx).trimEnd()
    val diffBody = output.substring(idx)
    return head to diffBody
}

@Composable
fun DiffView(diff: String, modifier: Modifier = Modifier) {
    // First line is "@@DIFF@@ path"; the real body follows.
    val firstNewline = diff.indexOf('\n')
    val header = if (firstNewline >= 0) diff.substring(0, firstNewline) else diff
    val body = if (firstNewline >= 0) diff.substring(firstNewline + 1) else ""
    val path = header.removePrefix(MARKER).trim()

    val darkBg = MaterialTheme.colorScheme.surfaceVariant
    val plusBg = Color(0x331F8B4C) // green 20%
    val minusBg = Color(0x33D64545) // red 20%
    val plusFg = Color(0xFF7EE2A6)
    val minusFg = Color(0xFFFFA8A8)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(darkBg)
    ) {
        if (path.isNotEmpty()) {
            Text(
                path,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        body.split('\n').forEach { line ->
            if (line.isEmpty()) return@forEach
            val (bg, fg) = when (line.firstOrNull()) {
                '+' -> plusBg to plusFg
                '-' -> minusBg to minusFg
                else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
            }
            Text(
                line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = fg,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .padding(horizontal = 10.dp, vertical = 1.dp)
            )
        }
    }
}
