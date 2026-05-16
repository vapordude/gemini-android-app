package nz.kaimahi.app.ui.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import nz.kaimahi.app.ui.chat.ChatViewModel

/**
 * Wires [MemoryBrowserScreen] to a v1 data source — saved projects.
 * The full topological-memory DAG lives in the Rust agent-core
 * MemoryStore; until the JNI bridge for that surface lands, projects
 * are a useful stand-in: each one is a node tagged by recency, with
 * tone derived from message count (small = quick · large = work).
 *
 * The real DAG view replaces this in a follow-up — same screen,
 * different data feed.
 */
@Composable
fun MemoryBrowserHost(
    chatViewModel: ChatViewModel,
    onDrawerOpen: () -> Unit,
) {
    val (entries, total) = remember(chatViewModel) {
        val all = chatViewModel.listSavedChats()
        val mapped = all.map { e ->
            MemoryEntry(
                id = e.name,
                label = e.name,
                tag = if (e.archived) "archived" else "project",
                whenLabel = relativeLabel(e.updatedAt),
                tone = when {
                    e.archived -> MemoryTone.Muted
                    e.messageCount >= 100 -> MemoryTone.Tool
                    e.messageCount >= 20 -> MemoryTone.Knowledge
                    else -> MemoryTone.Person
                },
            )
        }
        mapped to all.size
    }
    MemoryBrowserScreen(
        entries = entries,
        totalCount = total,
        onDrawerOpen = onDrawerOpen,
        onSearch = { /* search wiring lands when the real MemoryStore JNI does */ },
        onEntryClick = { entry -> chatViewModel.resumeChat(entry.id) },
    )
}

private fun relativeLabel(epochMs: Long): String {
    val delta = (System.currentTimeMillis() - epochMs).coerceAtLeast(0)
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    val weeks = days / 7
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days == 1L -> "1d"
        days < 7 -> "${days}d"
        weeks < 5 -> "${weeks}w"
        else -> "long"
    }
}
