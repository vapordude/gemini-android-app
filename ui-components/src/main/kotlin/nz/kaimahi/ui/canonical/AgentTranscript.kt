package nz.kaimahi.ui.canonical

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import nz.kaimahi.domain.AgentError
import nz.kaimahi.domain.AgentEvent
import nz.kaimahi.domain.Emphasis
import nz.kaimahi.domain.ErrorKind
import nz.kaimahi.domain.Hint
import nz.kaimahi.domain.Tone
import nz.kaimahi.ui.KaimahiExtendedColors
import nz.kaimahi.ui.LocalKaimahiColors

/**
 * Canonical renderer for the agent's typed event stream. The agent
 * already emits [AgentEvent]; this composable is exhaustive over the
 * sealed type — adding a variant requires updating both sides in the
 * same commit. Pure-data → Compose; no schema, no parser.
 *
 * The "fill out a form, GUI diffuses the data" pattern in Kaimahi is
 * literally this: the AI emits typed events, the UI renders each one
 * canonically. Streaming = appending = animation.
 *
 * Operators drop this into an `AppScreen` body or any scrollable
 * container.
 */
@Composable
fun AgentTranscript(
    events: List<AgentEvent>,
    modifier: Modifier = Modifier,
    onApprove: (callId: String) -> Unit = {},
    onReject: (callId: String) -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        events.forEach { event ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 }),
            ) {
                AgentEventCard(event = event, onApprove = onApprove, onReject = onReject)
            }
        }
    }
}

/**
 * One event → one canonical card. Each variant has a fixed
 * presentation; if a new variant is added upstream, the `when` stops
 * compiling and forces this file to update.
 */
@Composable
fun AgentEventCard(
    event: AgentEvent,
    modifier: Modifier = Modifier,
    onApprove: (callId: String) -> Unit = {},
    onReject: (callId: String) -> Unit = {},
) {
    when (event) {
        is AgentEvent.Thinking -> ThinkingCard(event.text, event.hint, modifier)
        is AgentEvent.Message -> MessageCard(event.text, event.hint, modifier)
        is AgentEvent.ToolCallPending ->
            ToolCallPendingCard(event, onApprove, onReject, modifier)
        is AgentEvent.ToolCallCompleted ->
            ToolCallCompletedCard(event, modifier)
        is AgentEvent.Error -> ErrorCard(event.error, modifier)
        AgentEvent.Done -> { /* terminal marker; nothing rendered */ }
    }
}

@Composable
private fun ThinkingCard(text: String, hint: Hint, modifier: Modifier) {
    val tokens = LocalKaimahiColors.current
    val color = toneColor(hint.tone, tokens) ?: tokens.muted
    val style = MaterialTheme.typography.bodyMedium.let {
        if (hint.emphasis == Emphasis.Strong)
            it.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
        else it
    }
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        style = style,
        color = color,
    )
}

@Composable
private fun MessageCard(text: String, hint: Hint, modifier: Modifier) {
    val tokens = LocalKaimahiColors.current
    val accent = toneColor(hint.tone, tokens)
    val borderColor = accent?.copy(alpha = 0.5f)
    val containerColor =
        if (accent != null) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
    val style = MaterialTheme.typography.bodyMedium.let {
        when (hint.emphasis) {
            Emphasis.Subtle -> it.copy(color = tokens.muted)
            Emphasis.Strong -> it.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            else -> it
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        tonalElevation = 1.dp,
        border = borderColor?.let { androidx.compose.foundation.BorderStroke(1.dp, it) },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            style = style,
        )
    }
}

private fun toneColor(tone: Tone, tokens: KaimahiExtendedColors): Color? = when (tone) {
    Tone.Default -> null
    Tone.Success -> tokens.success
    Tone.Warn -> tokens.warn
    Tone.Info -> tokens.brand
    Tone.Learning -> tokens.signal
    Tone.Danger -> tokens.danger
}

@Composable
private fun ToolCallPendingCard(
    event: AgentEvent.ToolCallPending,
    onApprove: (String) -> Unit,
    onReject: (String) -> Unit,
    modifier: Modifier,
) {
    val tokens = LocalKaimahiColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = tokens.act.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, tokens.act.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Tool call: ${event.name}",
                style = MaterialTheme.typography.labelMedium,
                color = tokens.act,
            )
            Text(
                event.argsJson,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Approval buttons are kept inline so the renderer is
            // self-contained; the operator wires `onApprove` to call
            // `AgentRuntime.resolveDecision(callId, ToolDecision.Approve)`.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.material3.TextButton(
                    onClick = { onReject(event.callId) },
                ) { Text("Reject") }
                androidx.compose.material3.Button(
                    onClick = { onApprove(event.callId) },
                ) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun ToolCallCompletedCard(event: AgentEvent.ToolCallCompleted, modifier: Modifier) {
    val tokens = LocalKaimahiColors.current
    val (label, color) = if (event.ok) {
        "Tool ok · ${event.outputLen}B" to tokens.success
    } else {
        "Tool failed · ${event.outputLen}B" to tokens.danger
    }
    Text(
        text = label,
        modifier = modifier.padding(horizontal = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = color,
    )
}

@Composable
private fun ErrorCard(error: AgentError, modifier: Modifier) {
    val tokens = LocalKaimahiColors.current
    val tint = when (error.kind) {
        ErrorKind.Network -> tokens.warn
        ErrorKind.Validation -> tokens.warn
        ErrorKind.Tool, ErrorKind.Inference -> tokens.danger
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(
                "${error.kind.tag} · ${error.source}",
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
            Text(
                error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
            )
        }
    }
}
