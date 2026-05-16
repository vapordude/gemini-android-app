package nz.kaimahi.app.ui.local

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nz.kaimahi.domain.TraceEvent
import nz.kaimahi.ui.AppScreen

@Composable
fun TraceViewerScreen(
    events: List<TraceEvent>,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    AppScreen(
        title = "Local traces",
        navigationIcon = navigationIcon,
        actions = actions,
    ) {
        Text(
            "Local-only, non-extractive. Typed fields only — never raw prompts or tool payloads.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) {
            Text("No trace events yet.")
        }
        events.forEach { ev ->
            Text(text = ev.toString(), modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
