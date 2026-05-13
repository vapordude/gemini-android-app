package nz.kaimahi.ui.canonical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nz.kaimahi.domain.EmdashDiff
import nz.kaimahi.ui.LocalKaimahiColors

/**
 * Canonical render of [EmdashDiff] — the dry-run result before any
 * destructive emdash mutation. Operators show this above the
 * "Apply" button so the user can read what's about to change.
 */
@Composable
fun EmdashDiffView(diff: EmdashDiff, modifier: Modifier = Modifier) {
    val tokens = LocalKaimahiColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Proposed changes", style = MaterialTheme.typography.titleMedium)
            DiffGroup("Adds", diff.adds, tokens.success)
            DiffGroup("Updates", diff.updates, tokens.signal)
            DiffGroup("Deletes", diff.deletes, tokens.danger)
            if (diff.warnings.isNotEmpty()) {
                Text(
                    "Warnings",
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.warn,
                )
                diff.warnings.forEach { w ->
                    Text("• $w", style = MaterialTheme.typography.bodySmall, color = tokens.warn)
                }
            }
        }
    }
}

@Composable
private fun DiffGroup(label: String, items: List<String>, color: androidx.compose.ui.graphics.Color) {
    if (items.isEmpty()) return
    Text(
        "$label (${items.size})",
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
    items.forEach { item ->
        Text("• $item", style = MaterialTheme.typography.bodySmall)
    }
}
