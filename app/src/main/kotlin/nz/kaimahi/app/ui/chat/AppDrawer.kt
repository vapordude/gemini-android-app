package nz.kaimahi.app.ui.chat

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
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import nz.kaimahi.app.ui.termux.openTermux
import nz.kaimahi.app.ui.termux.startGeminiCliLoginInTermux
import nz.kaimahi.ui.KaimahiBrand
import nz.kaimahi.ui.KaimahiLogo
import nz.kaimahi.ui.KaimahiLogoStyle

@Composable
fun AppDrawer(
    viewModel: ChatViewModel,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChats: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLocalTraces: () -> Unit,
    onOpenDeploymentConfigs: () -> Unit
) {
    val context = LocalContext.current
    val workspaceLabel by viewModel.workspaceLabel.collectAsState()
    val model by viewModel.model.collectAsState()

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.setProjectFolder(uri.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KaimahiLogo(size = 40.dp, style = KaimahiLogoStyle.Brand)
                Spacer(Modifier.width(12.dp))
                Text(
                    KaimahiBrand.NAME,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Serif
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                model,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                workspaceLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Conversation")
        DrawerItem(Icons.Default.ChatBubble, "Saved chats") {
            onClose(); onOpenChats()
        }
        DrawerItem(Icons.Default.FileDownload, "Export as Markdown") {
            onClose(); shareMarkdown(context, viewModel.exportAsMarkdown())
        }
        DrawerItem(Icons.Default.Folder, "Pick project folder") {
            onClose(); folderLauncher.launch(null)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Tools & shell")
        DrawerItem(Icons.Default.Terminal, "Open Termux") {
            onClose(); openTermux(context)
        }
        DrawerItem(Icons.Default.Terminal, "Gemini CLI login") {
            onClose(); startGeminiCliLoginInTermux(context)
        }
        DrawerItem(Icons.Default.BugReport, "Local traces") {
            onClose(); onOpenLocalTraces()
        }
        DrawerItem(Icons.Default.Build, "Deployment configs") {
            onClose(); onOpenDeploymentConfigs()
        }
        DrawerItem(Icons.Default.Code, "Source code") {
            onClose(); openUrl(context, "https://github.com/vapordude/gemini-android-app")
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("App")
        DrawerItem(Icons.Default.Settings, "Settings") {
            onClose(); onOpenSettings()
        }
        DrawerItem(Icons.Default.HelpOutline, "Help") {
            onClose(); openUrl(context, "https://ai.google.dev/gemini-api/docs")
        }
        DrawerItem(Icons.Default.Article, "Docs") {
            onClose(); openUrl(context, "https://ai.google.dev/gemini-api/docs")
        }
        DrawerItem(Icons.Default.BugReport, "Report a bug") {
            onClose(); openUrl(context, "https://github.com/vapordude/gemini-android-app/issues/new")
        }
        DrawerItem(Icons.Default.PrivacyTip, "Privacy") {
            onClose(); openUrl(context, "https://policies.google.com/privacy")
        }
        DrawerItem(Icons.Default.Info, "About") {
            onClose(); onOpenAbout()
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Account")
        DrawerItem(Icons.Default.Logout, "Sign out / change API key") {
            onClose(); viewModel.signOut()
        }
        DrawerItem(Icons.Default.ExitToApp, "Quit") {
            onClose()
            (context as? android.app.Activity)?.finish()
        }
    }
}

@Composable
private fun DrawerItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
        colors = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent
        )
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
fun AboutDialog(viewModel: ChatViewModel, onDismiss: () -> Unit) {
    val model by viewModel.model.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Kaimahi") },
        text = {
            Column {
                Text(
                    "Native Android client. Runs Gemma 4 on-device through the " +
                        "Kaimahi runtime, with the Gemini function-calling API " +
                        "available as a cloud fallback.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Text("Model: $model", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Termux installed: ${if (viewModel.termuxInstalled) "yes" else "no"}",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "Tools registered: ${viewModel.availableTools.size}",
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "github.com/vapordude/gemini-android-app",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun shareMarkdown(context: Context, markdown: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/markdown")
        .putExtra(Intent.EXTRA_TEXT, markdown)
        .putExtra(Intent.EXTRA_SUBJECT, "Kaimahi conversation")
    runCatching {
        context.startActivity(
            Intent.createChooser(intent, "Export conversation")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
