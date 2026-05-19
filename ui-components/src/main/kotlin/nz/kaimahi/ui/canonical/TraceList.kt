package nz.kaimahi.ui.canonical

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nz.kaimahi.domain.TraceEvent
import nz.kaimahi.ui.LocalKaimahiColors

/**
 * Canonical render of [TraceEvent]. The variants come straight from
 * `domain/TraceEvent.kt`; this composable is exhaustive over the
 * sealed type.
 */
@Composable
fun TraceList(events: List<TraceEvent>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        events.forEach { TraceEventRow(it) }
    }
}

@Composable
fun TraceEventRow(event: TraceEvent, modifier: Modifier = Modifier) {
    val tokens = LocalKaimahiColors.current
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            ts(event.timestampMs),
            style = MaterialTheme.typography.labelSmall,
            color = tokens.muted,
        )
        when (event) {
            is TraceEvent.ModelLoaded -> Text(
                buildString {
                    append("model_loaded arch=").append(event.archTag)
                    append(" isa=").append(event.isa)
                    append(" threads=").append(event.threads)
                    if (event.residentBytes > 0) {
                        val mb = event.residentBytes.toDouble() / (1024.0 * 1024.0)
                        append(" rss=")
                        if (mb >= 1024.0) {
                            append("%.1f".format(mb / 1024.0)).append("GB")
                        } else {
                            append(mb.toLong()).append("MB")
                        }
                    }
                    append(if (event.mmapPinned) " pinned" else " reclaimable")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            is TraceEvent.GenerateFinished -> Text(
                "generate ${event.tokens}t / ${event.durationMs}ms (${"%.1f".format(event.tokensPerSec)} t/s)",
                style = MaterialTheme.typography.bodySmall,
            )
            is TraceEvent.AgentIteration -> Text(
                "agent iter=${event.iter}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.muted,
            )
            is TraceEvent.ToolCall -> Text(
                "tool ${event.name} · ${if (event.ok) "ok" else "fail"} · ${event.durationMs}ms",
                style = MaterialTheme.typography.bodySmall,
                color = if (event.ok) tokens.success else tokens.danger,
            )
            is TraceEvent.Error -> Text(
                "error ${event.kind}: ${event.message}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.danger,
            )
        }
    }
}

private fun ts(ms: Long): String {
    // hh:mm:ss.SSS — non-extractive: shows only relative time-of-day,
    // never a session-identifying string.
    val secInDay = (ms / 1000) % 86_400
    val h = secInDay / 3600
    val m = (secInDay % 3600) / 60
    val s = secInDay % 60
    val mss = ms % 1000
    return "%02d:%02d:%02d.%03d".format(h, m, s, mss)
}
