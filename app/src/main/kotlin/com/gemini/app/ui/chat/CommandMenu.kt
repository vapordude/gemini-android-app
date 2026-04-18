package com.gemini.app.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

private data class QuickAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val description: String
)

private val actions = listOf(
    QuickAction("help", "Help", Icons.Default.HelpOutline, "What can Gemini do?"),
    QuickAction("stats", "Stats", Icons.Default.BugReport, "Session counters"),
    QuickAction("copy", "Copy last", Icons.Default.ContentCopy, "Last reply → clipboard"),
    QuickAction("share", "Share last", Icons.Default.Share, "Share last reply"),
    QuickAction("compress", "Compress", Icons.Default.Compress, "Summarise & continue"),
    QuickAction("memory", "Memory", Icons.Default.Memory, "Edit GEMINI.md"),
    QuickAction("history", "History", Icons.Default.History, "Recent prompts"),
    QuickAction("reset", "Reset", Icons.Default.Refresh, "Clear context + history"),
    QuickAction("clear", "Clear UI", Icons.Default.Delete, "Clear messages on screen"),
    QuickAction("folder", "Folder", Icons.Default.Folder, "Pick workspace folder"),
    QuickAction("shell", "Shell test", Icons.Default.Terminal, "Run echo via Termux"),
    QuickAction("tools", "Tools", Icons.Default.Build, "See enabled tools"),
    QuickAction("settings", "Settings", Icons.Default.Settings, "Model, approvals, key"),
    QuickAction("about", "About", Icons.Default.Info, "Version & status")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<DialogContent?>(null) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.setProjectFolder(uri.toString())
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Quick actions",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "Every CLI slash-command maps to a tile below.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.heightIn(min = 260.dp, max = 520.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(actions) { action ->
                    ActionTile(action) {
                        when (action.id) {
                            "help" -> dialog = helpDialog()
                            "stats" -> dialog = statsDialog(viewModel)
                            "copy" -> {
                                val text = viewModel.lastAssistantText()
                                if (text.isNullOrBlank()) {
                                    toast(context, "No assistant reply yet")
                                } else {
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
                            "history" -> dialog = historyDialog(viewModel)
                            "reset" -> { viewModel.resetSession(); onDismiss() }
                            "clear" -> { viewModel.clearMessages(); onDismiss() }
                            "folder" -> folderLauncher.launch(null)
                            "shell" -> {
                                viewModel.sendMessage(
                                    "Use run_shell_command to execute: echo hello from termux && uname -a"
                                )
                                onDismiss()
                            }
                            "tools", "settings" -> onOpenSettings()
                            "about" -> dialog = aboutDialog(viewModel)
                        }
                    }
                }
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
                Icon(action.icon, contentDescription = action.label)
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

private data class DialogContent(val title: String, val lines: List<String>, val mono: Boolean = false)

private fun helpDialog(): DialogContent = DialogContent(
    title = "What can Gemini do?",
    lines = listOf(
        "• Read / write / edit / delete files in the workspace",
        "• Glob + grep across the workspace",
        "• Run shell commands through Termux",
        "• Call tools automatically during a reply",
        "",
        "Tiles above map the CLI slash-commands to buttons:",
        "reset = /clear  ·  compress = /compress  ·  stats = /stats",
        "memory = /memory  ·  folder = /directory  ·  tools = /tools",
        "help = /help  ·  about = /about"
    )
)

private fun statsDialog(vm: ChatViewModel): DialogContent {
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

private fun historyDialog(vm: ChatViewModel): DialogContent {
    val prompts = vm.messages.filter { it.isUser }.takeLast(10).map { it.text }
    val lines = if (prompts.isEmpty()) listOf("(no prompts yet)")
        else prompts.mapIndexed { i, p -> "${i + 1}. ${p.take(80)}" }
    return DialogContent(title = "Recent prompts", lines = lines)
}

private fun aboutDialog(vm: ChatViewModel): DialogContent = DialogContent(
    title = "About Gemini UI",
    lines = listOf(
        "Native Android frontend for the Gemini function-calling API.",
        "",
        "Model: ${vm.model.value}",
        "Termux installed: ${if (vm.termuxInstalled) "yes" else "no"}",
        "Tools registered: ${vm.availableTools.size}",
        "",
        "Source: github.com/aciderix/gemini-android-app"
    )
)

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

@Suppress("unused")
private fun openUrl(context: Context, url: String) {
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
