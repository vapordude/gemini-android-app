package com.gemini.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class CliCommand(
    val id: String,
    val name: String,
    val icon: ImageVector,
    val description: String
)

val availableCommands = listOf(
    CliCommand("reset", "Reset", Icons.Default.Refresh, "Clear current session"),
    CliCommand("history", "History", Icons.Default.List, "Show previous messages"),
    CliCommand("context", "Context", Icons.Default.Info, "See active context files"),
    CliCommand("skills", "Skills", Icons.Default.Star, "Manage active skills"),
    CliCommand("settings", "Settings", Icons.Default.Settings, "Edit configuration"),
    CliCommand("clear", "Clear", Icons.Default.Delete, "Wipe local data")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandBottomSheet(
    onCommandClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                "Gemini Commands",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.height(300.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(availableCommands) { command ->
                    CommandItem(command) {
                        onCommandClick(command.id)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
fun CommandItem(command: CliCommand, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(command.icon, contentDescription = command.name)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(command.name, style = MaterialTheme.typography.labelMedium)
    }
}
