package com.gemini.app.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

private data class QuickAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val description: String
)

private val actions = listOf(
    QuickAction("reset", "Reset", Icons.Default.Refresh, "Clear history + memory"),
    QuickAction("clear", "Clear UI", Icons.Default.Delete, "Clear messages on screen"),
    QuickAction("folder", "Folder", Icons.Default.Folder, "Pick workspace folder"),
    QuickAction("tools", "Tools", Icons.Default.Build, "See enabled tools"),
    QuickAction("settings", "Settings", Icons.Default.Settings, "Model, approvals, key"),
    QuickAction("about", "About", Icons.Default.Info, "Version & Termux status")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
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
                modifier = Modifier.padding(bottom = 12.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.height(260.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(actions) { action ->
                    ActionTile(action) {
                        when (action.id) {
                            "reset" -> { viewModel.resetSession(); onDismiss() }
                            "clear" -> { viewModel.clearMessages(); onDismiss() }
                            "folder" -> folderLauncher.launch(null)
                            "settings" -> onOpenSettings()
                            "tools" -> onOpenSettings()
                            "about" -> onOpenSettings()
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
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
