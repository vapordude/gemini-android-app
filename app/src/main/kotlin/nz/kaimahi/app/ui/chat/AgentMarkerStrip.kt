package nz.kaimahi.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import nz.kaimahi.domain.AgentMarker
import nz.kaimahi.domain.AgentMarkerKind
import nz.kaimahi.domain.AgentMarkerStatus

/**
 * Inline strip of typed agent activity markers. Each marker is a one-
 * line row showing what the agent is doing right now (reading a file,
 * grepping, editing, running a shell command). Rows with detail
 * payload are tappable and expand to show the detail in a monospace
 * inset surface — the diff, the stdout, the match list, the arguments.
 *
 * Visually distinct from chat bubbles: smaller, denser, subdued
 * background so the eye reads them as *activity*, not *speech*. The
 * status enum drives the trailing indicator (spinner/check/cross) so
 * the user can scan the strip and see at a glance which steps are
 * done.
 */
/**
 * Emit one LazyColumn item per marker, each with a stable id key so
 * the LazyColumn actually virtualises long lists (50+ markers in a
 * heavy local-agent session) instead of recomposing the whole strip
 * on every state change. Call this from inside a LazyListScope block.
 */
fun LazyListScope.agentMarkerItems(markers: List<AgentMarker>) {
    if (markers.isEmpty()) return
    items(markers, key = { it.id }) { marker ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        ) {
            AgentMarkerRow(marker)
        }
    }
}

/**
 * Standalone strip rendering for non-LazyColumn contexts (previews,
 * sheets, etc.). Inside the chat list, prefer [agentMarkerItems] so
 * each row gets its own virtualised LazyColumn item.
 */
@Composable
fun AgentMarkerStrip(
    markers: List<AgentMarker>,
    modifier: Modifier = Modifier,
) {
    if (markers.isEmpty()) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        markers.forEach { marker ->
            AgentMarkerRow(marker)
        }
    }
}

@Composable
private fun AgentMarkerRow(marker: AgentMarker) {
    // Per-row expansion state, keyed to the marker id so a list shuffle
    // doesn't leak expansion between rows.
    var expanded by rememberSaveable(marker.id) { mutableStateOf(false) }
    val hasDetail = !marker.detail.isNullOrBlank()

    val borderColor = when (marker.status) {
        AgentMarkerStatus.Running -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        AgentMarkerStatus.Done -> MaterialTheme.colorScheme.outlineVariant
        AgentMarkerStatus.Failed -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, MaterialTheme.shapes.small)
            .then(
                if (hasDetail) Modifier.clickable { expanded = !expanded }
                else Modifier
            ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = iconFor(marker.kind),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    marker.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(marker.status)
                if (hasDetail) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            AnimatedVisibility(visible = expanded && hasDetail) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    Text(
                        marker.detail.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: AgentMarkerStatus) {
    when (status) {
        AgentMarkerStatus.Running -> CircularProgressIndicator(
            strokeWidth = 1.5.dp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        AgentMarkerStatus.Done -> Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Done",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        AgentMarkerStatus.Failed -> Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
    }
}

private fun iconFor(kind: AgentMarkerKind): ImageVector = when (kind) {
    AgentMarkerKind.Responding -> Icons.Default.Psychology
    AgentMarkerKind.Thinking -> Icons.Default.Psychology
    AgentMarkerKind.ReadingFile -> Icons.Default.Folder
    AgentMarkerKind.WritingFile -> Icons.Default.Edit
    AgentMarkerKind.EditingFile -> Icons.Default.Edit
    AgentMarkerKind.DeletingFile -> Icons.Default.Delete
    AgentMarkerKind.ListingDir -> Icons.Default.Folder
    AgentMarkerKind.Globbing -> Icons.Default.TravelExplore
    AgentMarkerKind.Grepping -> Icons.Default.Search
    AgentMarkerKind.ShellCommand -> Icons.Default.Terminal
    AgentMarkerKind.GeneratingImage -> Icons.Default.Image
    AgentMarkerKind.Tool -> Icons.Default.AutoFixHigh
}
