package com.gemini.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gemini.app.ui.chat.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val currentModel by viewModel.model.collectAsState()
    val autoApprove by viewModel.autoApprove.collectAsState()
    val workspaceLabel by viewModel.workspaceLabel.collectAsState()

    var customModel by remember { mutableStateOf("") }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.setProjectFolder(uri.toString())
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            SectionTitle("Account")
            Text(
                "Use an API key from Google AI Studio. Sign in with your Google account there " +
                    "to create one, then paste it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openUrl(context, "https://aistudio.google.com/app/apikey") }) {
                    Icon(Icons.Default.Key, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Get API key")
                }
                OutlinedButton(onClick = {
                    openUrl(context, "https://accounts.google.com/signin?continue=" +
                        Uri.encode("https://aistudio.google.com/app/apikey"))
                }) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Sign in")
                }
            }

            Divider(Modifier.padding(vertical = 12.dp))

            SectionTitle("Model")
            viewModel.availableModels.forEach { name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = name == currentModel,
                        onClick = { viewModel.setModel(name) }
                    )
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Or type a custom model ID",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customModel,
                    onValueChange = { customModel = it },
                    placeholder = { Text("e.g. gemini-3.0-pro-exp") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (customModel.isNotBlank()) {
                            viewModel.setModel(customModel.trim())
                            customModel = ""
                        }
                    }
                ) { Text("Use") }
            }

            Divider(Modifier.padding(vertical = 12.dp))

            SectionTitle("Workspace")
            Text(
                workspaceLabel,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.padding(vertical = 4.dp)
            )
            OutlinedButton(
                onClick = { folderLauncher.launch(null) },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Pick folder")
            }

            Divider(Modifier.padding(vertical = 12.dp))

            SectionTitle("Tool approvals")
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-approve destructive tools", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Skips the approval dialog for write/edit/delete/shell.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoApprove,
                    onCheckedChange = { viewModel.setAutoApprove(it) }
                )
            }

            Divider(Modifier.padding(vertical = 12.dp))

            SectionTitle("Tools available")
            viewModel.availableTools.forEach { tool ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tool.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val badge = tool.category.name.lowercase() + if (tool.destructive) " · write" else ""
                    Surface(
                        color = if (tool.destructive) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Divider(Modifier.padding(vertical = 12.dp))

            TermuxSection(viewModel)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TermuxSection(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val installed = viewModel.termuxInstalled

    SectionTitle("Termux shell")
    if (!installed) {
        Text(
            "Termux is not installed. It powers real shell commands " +
                "(git, gradle, curl…). Install it from F-Droid (the Play Store build is outdated).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openUrl(context, "https://f-droid.org/packages/com.termux/") }) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Install Termux")
            }
            OutlinedButton(onClick = { openUrl(context, "https://f-droid.org/packages/com.termux.api/") }) {
                Text("+ Termux:API")
            }
        }
        return
    }

    Text(
        "Termux detected. To let this app run commands, you must enable " +
            "allow-external-apps once:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(6.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "1. Open Termux\n" +
                "2. Run: mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties\n" +
                "3. Run: termux-reload-settings\n" +
                "4. Come back and tap \"Test shell\"",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(10.dp)
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { openTermux(context) }) {
            Icon(Icons.Default.Terminal, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Open Termux")
        }
        OutlinedButton(onClick = {
            viewModel.sendMessage("Use run_shell_command to execute: echo hello from termux")
        }) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Test shell")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openTermux(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage("com.termux")
    if (launch != null) {
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
    openUrl(context, "https://f-droid.org/packages/com.termux/")
}

@Suppress("unused")
private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
