package nz.kaimahi.ui.canonical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nz.kaimahi.domain.RuntimeInfo
import nz.kaimahi.ui.BadgeKind
import nz.kaimahi.ui.KaimahiBadge
import nz.kaimahi.ui.LocalKaimahiColors

/**
 * Canonical render of [RuntimeInfo] — what `/info` returns. Shows:
 *   - the loaded model (or "no model loaded") as a kauri badge
 *   - the active ISA tier as a labelled value
 *   - the delegate as a pill (cpu / qnn / neural-engine / …)
 *   - thread count
 *
 * Pure-data → Compose; no schema, no parser.
 */
@Composable
fun RuntimeInfoPanel(
    info: RuntimeInfo,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Runtime", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val model = info.modelLoaded
                if (model != null) {
                    KaimahiBadge(kind = BadgeKind.Local, label = model.archTag)
                } else {
                    KaimahiBadge(kind = BadgeKind.Local, label = "no model")
                }
                KaimahiBadge(kind = BadgeKind.Delegate, label = info.isa)
            }
            KvRow(label = "version", value = info.version)
            KvRow(label = "arch", value = info.arch)
            KvRow(label = "threads", value = info.threads.toString())
            val model = info.modelLoaded
            if (model != null) {
                KvRow(label = "model.id", value = model.id)
                KvRow(label = "model.path", value = model.path)
            }
        }
    }
}

@Composable
private fun KvRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = LocalKaimahiColors.current.muted,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
