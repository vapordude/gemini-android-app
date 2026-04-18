package com.gemini.app.ui.settings

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
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gemini.app.ui.chat.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val currentModel by viewModel.model.collectAsState()
    val autoApprove by viewModel.autoApprove.collectAsState()
    val workspaceLabel by viewModel.workspaceLabel.collectAsState()

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

            SectionTitle("Termux")
            Text(
                if (viewModel.termuxInstalled) "Detected. Enable allow-external-apps in " +
                    "~/.termux/termux.properties and grant RUN_COMMAND permission."
                else "Not installed. Install Termux from F-Droid to run shell commands.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

