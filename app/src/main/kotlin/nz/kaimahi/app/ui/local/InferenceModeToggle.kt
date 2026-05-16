package nz.kaimahi.app.ui.local

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun InferenceModeToggle(
    selected: InferenceMode,
    onSelect: (InferenceMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == InferenceMode.CLOUD_GEMINI,
            onClick = { onSelect(InferenceMode.CLOUD_GEMINI) },
            label = { Text("Cloud Gemini") },
        )
        FilterChip(
            selected = selected == InferenceMode.LOCAL_AGENT,
            onClick = { onSelect(InferenceMode.LOCAL_AGENT) },
            label = { Text("Local agent") },
        )
    }
}
