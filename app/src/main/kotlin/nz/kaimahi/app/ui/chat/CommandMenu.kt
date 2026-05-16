package nz.kaimahi.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

internal data class QuickAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val description: String
)

private data class ActionGroup(val title: String, val items: List<QuickAction>)

private val groups = listOf(
    ActionGroup(
        "Reply",
        listOf(
            QuickAction("copy", "Copy last", Icons.Default.ContentCopy, "Last reply → clipboard"),
            QuickAction("share", "Share", Icons.Default.Share, "Share last reply"),
            QuickAction("history", "History", Icons.Default.History, "Recent prompts"),
        )
    ),
    ActionGroup(
        "Session",
        listOf(
            QuickAction("compress", "Compress", Icons.Default.Compress, "Summarise & restart"),
            QuickAction("reset", "Reset", Icons.Default.Refresh, "/clear context"),
            QuickAction("clearui", "Clear UI", Icons.Default.Delete, "Wipe screen only"),
            QuickAction("chats", "Saved chats", Icons.Default.Save, "Save / resume"),
            QuickAction("stats", "Stats", Icons.Default.Info, "/stats"),
        )
    ),
    ActionGroup(
        "Workspace",
        listOf(
            QuickAction("memory", "Memory", Icons.Default.Memory, "Read GEMINI.md"),
            QuickAction("init", "Init", Icons.Default.NoteAdd, "Create GEMINI.md"),
            QuickAction("shell", "Shell test", Icons.Default.Terminal, "Echo via Termux"),
        )
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<DialogContent?>(null) }
    var showChats by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateOf(emptySet<String>()) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Quick actions",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "Conversation shortcuts. Settings, account & help live in the side menu (☰).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            groups.forEach { group ->
                AccordionSection(
                    title = group.title,
                    expanded = group.title in expanded.value,
                    onToggle = {
                        expanded.value = if (group.title in expanded.value)
                            expanded.value - group.title else expanded.value + group.title
                    }
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(group.items) { action ->
                            ActionTile(action) {
                                handleAction(
                                    action.id, viewModel, context,
                                    onShowDialog = { dialog = it },
                                    onShowChats = { showChats = true },
                                    onDismiss = onDismiss
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    dialog?.let { d ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = {
                TextButton(onClick = { dialog = null }) { Text("Close") }
            },
            title = { Text(d.title) },
            text = {
                Column {
                    d.lines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = if (d.mono) FontFamily.Monospace else null
                            )
                        )
                    }
                }
            }
        )
    }

    if (showChats) {
        ChatsDialog(
            viewModel = viewModel,
            onDismiss = { showChats = false },
            onResumed = {
                showChats = false
                onDismiss()
            }
        )
    }
}

private fun handleAction(
    id: String,
    viewModel: ChatViewModel,
    context: Context,
    onShowDialog: (DialogContent) -> Unit,
    onShowChats: () -> Unit,
    onDismiss: () -> Unit
) {
    when (id) {
        "stats" -> onShowDialog(statsDialog(viewModel))
        "history" -> onShowDialog(historyDialog(viewModel))
        "copy" -> {
            val text = viewModel.lastAssistantText()
            if (text.isNullOrBlank()) toast(context, "No assistant reply yet")
            else {
                copyToClipboard(context, "gemini-reply", text)
                toast(context, "Copied to clipboard")
            }
        }
        "share" -> {
            val text = viewModel.lastAssistantText()
            if (text.isNullOrBlank()) toast(context, "No assistant reply yet")
            else shareText(context, text)
        }
        "compress" -> { viewModel.compressSession(); onDismiss() }
        "memory" -> {
            viewModel.sendMessage(
                "Open GEMINI.md in the workspace with read_file. " +
                    "If it does not exist, create it with write_file containing: " +
                    "\"# Gemini memory\\n\". Then tell me what it contains."
            )
            onDismiss()
        }
        "init" -> {
            viewModel.sendMessage(
                "Create GEMINI.md in the workspace using write_file with this " +
                    "content:\n# Gemini memory\n\n- Project style & commands\n" +
                    "- Key decisions\n- Known gotchas\n\nThen confirm."
            )
            onDismiss()
        }
        "reset" -> { viewModel.resetSession(); onDismiss() }
        "clearui" -> { viewModel.clearMessages(); onDismiss() }
        "shell" -> {
            viewModel.sendMessage(
                "Use run_shell_command to execute: echo hello from termux && uname -a"
            )
            onDismiss()
        }
        "chats" -> onShowChats()
    }
}

@Composable
private fun AccordionSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
fun ChatsDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onResumed: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf(viewModel.listSavedChats()) }
    val fmt = remember { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Saved chats") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Name (e.g. bug-hunt)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            viewModel.saveChat(name.trim())
                            entries = viewModel.listSavedChats()
                            name = ""
                            toast(context, "Saved")
                        }
                    ) { Text("Save") }
                }
                Spacer(Modifier.height(12.dp))
                if (entries.isEmpty()) {
                    Text(
                        "No saved chats yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        entries.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "${entry.messageCount} msgs · ${fmt.format(java.util.Date(entry.updatedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    viewModel.resumeChat(entry.name)
                                    onResumed()
                                }) {
                                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(2.dp))
                                    Text("Resume")
                                }
                                TextButton(onClick = {
                                    viewModel.deleteChat(entry.name)
                                    entries = viewModel.listSavedChats()
                                }) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ActionTile(action: QuickAction, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    action.icon,
                    contentDescription = action.label,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(action.label, style = MaterialTheme.typography.labelMedium)
        Text(
            action.description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal data class DialogContent(val title: String, val lines: List<String>, val mono: Boolean = false)

internal fun statsDialog(vm: ChatViewModel): DialogContent {
    val s = vm.stats()
    return DialogContent(
        title = "Session stats",
        lines = listOf(
            "Model: ${vm.model.value}",
            "Workspace: ${vm.workspaceLabel.value}",
            "Messages: ${s.userMessages + s.modelMessages}",
            "  · user: ${s.userMessages}",
            "  · model: ${s.modelMessages}",
            "Tool events: ${s.toolEvents}",
            "Total chars: ${s.totalChars}"
        ),
        mono = true
    )
}

internal fun historyDialog(vm: ChatViewModel): DialogContent {
    val prompts = vm.messages.filter { it.isUser }.takeLast(10).map { it.text }
    val lines = if (prompts.isEmpty()) listOf("(no prompts yet)")
        else prompts.mapIndexed { i, p -> "${i + 1}. ${p.take(80)}" }
    return DialogContent(title = "Recent prompts", lines = lines)
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

internal fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
