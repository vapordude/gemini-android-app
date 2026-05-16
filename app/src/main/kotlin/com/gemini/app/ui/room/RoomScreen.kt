package com.gemini.app.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gemini.domain.Persona
import com.gemini.domain.RoomMessage
import com.gemini.domain.TurnPolicy

/**
 * Multi-model chat room. Users add personas as seats; the engine picks
 * who responds via the configured [TurnPolicy] (or by @persona-name
 * mentions). Each seat shares the routed GeminiCore so sensitivity-aware
 * routing still applies to every turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(vm: RoomViewModel) {
    val personas by vm.personas.collectAsState()
    val seats by vm.activeSeats.collectAsState()
    val messages by vm.messages.collectAsState()
    val isSending by vm.isSending.collectAsState()
    val policy by vm.policy.collectAsState()

    var draft by remember { mutableStateOf("") }
    var personaMenuOpen by remember { mutableStateOf(false) }
    var policyMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header: seats + add button + policy selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Chat room", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier.padding(end = 4.dp),
            ) {
                AssistChip(
                    onClick = { policyMenuOpen = true },
                    label = { Text(policy.toLabel()) },
                )
                DropdownMenu(
                    expanded = policyMenuOpen,
                    onDismissRequest = { policyMenuOpen = false },
                ) {
                    TurnPolicy.values().forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.toLabel()) },
                            onClick = { vm.setPolicy(p); policyMenuOpen = false },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Seat chips row + Add button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            seats.forEach { seat ->
                AssistChip(
                    onClick = { },
                    label = {
                        Text("${seat.persona.avatar} ${seat.persona.name}")
                    },
                    trailingIcon = {
                        IconButton(onClick = { vm.removeSeat(seat.seatId) }) {
                            Icon(Icons.Default.Close, contentDescription = "remove")
                        }
                    },
                )
                Spacer(Modifier.width(6.dp))
            }
            Box {
                AssistChip(
                    onClick = { personaMenuOpen = true },
                    label = { Text("Add seat") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                )
                DropdownMenu(
                    expanded = personaMenuOpen,
                    onDismissRequest = { personaMenuOpen = false },
                ) {
                    val available = personas.filter { p -> seats.none { it.persona.id == p.id } }
                    if (available.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("All personas seated") },
                            onClick = { personaMenuOpen = false },
                        )
                    } else {
                        available.forEach { p ->
                            DropdownMenuItem(
                                text = { Text("${p.avatar} ${p.name}") },
                                onClick = { vm.addSeat(p); personaMenuOpen = false },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // Messages
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { msg -> MessageBubble(msg, seats) }
            if (isSending) {
                item {
                    Text(
                        "…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        // Composer
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Type @persona-name to direct, otherwise round-robin") },
                modifier = Modifier.weight(1f),
                enabled = !isSending && seats.isNotEmpty(),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val t = draft.trim()
                    if (t.isNotEmpty()) { vm.send(t); draft = "" }
                },
                enabled = !isSending && seats.isNotEmpty() && draft.isNotBlank(),
            ) {
                Icon(Icons.Default.Send, contentDescription = "send")
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: RoomMessage, seats: List<com.gemini.domain.ChatRoomSeat>) {
    val speaker = msg.seatId?.let { id -> seats.firstOrNull { it.seatId == id }?.persona }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            val label = if (speaker == null) "You" else "${speaker.avatar} ${speaker.name}"
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(msg.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun TurnPolicy.toLabel(): String = when (this) {
    TurnPolicy.ROUND_ROBIN -> "Round-robin"
    TurnPolicy.MENTIONED_ONLY -> "Mentions only"
    TurnPolicy.MENTION_OR_ROUND_ROBIN -> "Mention or RR"
    TurnPolicy.EVERYONE -> "Everyone replies"
}

