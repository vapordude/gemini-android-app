package com.gemini.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gemini.app.ui.chat.ChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeChange: (ThemeMode) -> Unit = {}
) {
    val context = LocalContext.current
    val currentModel by viewModel.model.collectAsState()
    val autoApprove by viewModel.autoApprove.collectAsState()
    val workspaceLabel by viewModel.workspaceLabel.collectAsState()
    val models by viewModel.availableModels.collectAsState()

    var customModel by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<String>()) }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.setProjectFolder(uri.toString())
    }

    fun toggle(name: String) {
        expanded = if (name in expanded) expanded - name else expanded + name
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            SettingsAccordion(
                title = "Account",
                icon = Icons.Default.AccountCircle,
                expanded = "Account" in expanded,
                onToggle = { toggle("Account") }
            ) {
                Text(
                    "API key from Google AI Studio. Sign in with your Google " +
                        "account there to create one, then paste it here.",
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
                        viewModel.signOut()
                        onDismiss()
                    }) { Text("Sign out") }
                }
            }

            SettingsAccordion(
                title = "Model",
                icon = Icons.Default.Memory,
                expanded = "Model" in expanded,
                onToggle = { toggle("Model") }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${models.size} available",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.refreshModels() }) { Text("Refresh") }
                }
                models.forEach { name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setModel(name) }
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
            }

            SettingsAccordion(
                title = "Workspace",
                icon = Icons.Default.Folder,
                expanded = "Workspace" in expanded,
                onToggle = { toggle("Workspace") }
            ) {
                Text(
                    workspaceLabel,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { folderLauncher.launch(null) }) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Pick folder")
                }
            }

            SettingsAccordion(
                title = "Tool approvals",
                icon = Icons.Default.Security,
                expanded = "Tool approvals" in expanded,
                onToggle = { toggle("Tool approvals") }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
            }

            SettingsAccordion(
                title = "Theme",
                icon = Icons.Default.Palette,
                expanded = "Theme" in expanded,
                onToggle = { toggle("Theme") }
            ) {
                ThemeMode.values().forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onThemeChange(mode) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = mode == themeMode,
                            onClick = { onThemeChange(mode) }
                        )
                        Text(mode.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            SettingsAccordion(
                title = "Tools available",
                icon = Icons.Default.Build,
                expanded = "Tools available" in expanded,
                onToggle = { toggle("Tools available") }
            ) {
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
                        val badge = tool.category.name.lowercase() +
                            if (tool.destructive) " · write" else ""
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
            }

            SettingsAccordion(
                title = "Termux shell",
                icon = Icons.Default.Terminal,
                expanded = "Termux shell" in expanded,
                onToggle = { toggle("Termux shell") }
            ) {
                TermuxBody(viewModel)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"),
    LIGHT("Light"),
    DARK("Dark")
}

@Composable
private fun SettingsAccordion(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    content()
                }
            }
        }
    }
}

private const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

private fun hasRunCommandPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, RUN_COMMAND_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

private enum class TermuxSource(val label: String, val isPlayStore: Boolean) {
    PLAY_STORE("Play Store (outdated, won't work)", true),
    F_DROID("F-Droid", false),
    GITHUB("GitHub / sideload", false),
    OTHER_STORE("Other store", false),
    UNKNOWN("Unknown source", false),
}

private fun detectTermuxSource(context: Context): TermuxSource {
    val pm = context.packageManager
    val installer = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo("com.termux").installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName("com.termux")
        }
    }.getOrNull()
    return when (installer) {
        "com.android.vending" -> TermuxSource.PLAY_STORE
        "org.fdroid.fdroid", "org.fdroid.basic", "com.aurora.store" -> TermuxSource.F_DROID
        null, "com.google.android.packageinstaller",
        "com.android.packageinstaller", "com.github.android" -> TermuxSource.GITHUB
        else -> TermuxSource.OTHER_STORE
    }
}

private fun termuxDeclaresRunCommand(context: Context): Boolean = runCatching {
    context.packageManager.getPermissionInfo(RUN_COMMAND_PERMISSION, 0)
    true
}.getOrDefault(false)

@Composable
private fun TermuxBody(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val installed = viewModel.termuxInstalled

    if (!installed) {
        Text(
            "Termux is not installed. It powers real shell commands " +
                "(git, gradle, curl…). Install from F-Droid — the Play Store " +
                "build is outdated and does NOT accept external commands.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
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

    val source = remember { detectTermuxSource(context) }
    val declaresPermission = remember { termuxDeclaresRunCommand(context) }
    var permissionGranted by remember { mutableStateOf(hasRunCommandPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            Toast.makeText(
                context,
                "Permission not granted. If no dialog appeared, your Termux " +
                    "build does not declare RUN_COMMAND — reinstall from F-Droid.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Text(
        "Three things must all be true for shell commands to work:",
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(Modifier.height(8.dp))

    TermuxStep(
        number = "1",
        title = "Termux build supports external commands",
        ok = !source.isPlayStore && declaresPermission,
        body = {
            Text(
                "Installed from: ${source.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (source.isPlayStore || !declaresPermission) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (source.isPlayStore)
                        "The Play Store version of Termux was abandoned in 2020. It " +
                            "does NOT register the RUN_COMMAND service or permission, " +
                            "which is why nothing appears under Permissions in Android " +
                            "Settings. Uninstall it and reinstall the F-Droid build."
                    else
                        "The installed Termux does not declare the RUN_COMMAND " +
                            "permission, so Android can't list it under Permissions. " +
                            "Reinstall Termux from F-Droid (or termux/termux-app on GitHub).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openUrl(context, "https://f-droid.org/packages/com.termux/") }) {
                        Icon(Icons.Default.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("F-Droid Termux")
                    }
                    OutlinedButton(onClick = {
                        openUrl(context, "https://github.com/termux/termux-app/releases/latest")
                    }) { Text("GitHub APK") }
                }
            } else {
                Text(
                    "Good — this build declares com.termux.permission.RUN_COMMAND.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )

    TermuxStep(
        number = "2",
        title = "Termux allows external apps",
        ok = null,
        body = {
            Text(
                "Open Termux and paste these two commands once (tap to copy):",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            TermuxCommandRow(
                step = "a.",
                command = "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties",
                context = context
            )
            TermuxCommandRow(
                step = "b.",
                command = "termux-reload-settings",
                context = context
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { openTermux(context) }) {
                Icon(Icons.Default.Terminal, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Open Termux")
            }
        }
    )

    TermuxStep(
        number = "3",
        title = "Android RUN_COMMAND permission",
        ok = permissionGranted,
        body = {
            when {
                permissionGranted -> Text(
                    "Granted. You should be able to run shell commands now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                !declaresPermission -> Text(
                    "Skipped — fix step 1 first. Android can't grant a permission " +
                        "that no installed app declares.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> {
                    Text(
                        "Android treats com.termux.permission.RUN_COMMAND as a " +
                            "dangerous permission — it must be granted explicitly. " +
                            "Tap Request and approve the popup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { permissionLauncher.launch(RUN_COMMAND_PERMISSION) }) {
                            Icon(Icons.Default.Security, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Request permission")
                        }
                        OutlinedButton(onClick = { openAppSettings(context) }) {
                            Text("Open app settings")
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "If \"Request\" does nothing, open app settings → Permissions. " +
                            "On some Android versions the entry is hidden under " +
                            "\"Unused permissions\" or \"More permissions\".",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    )

    Spacer(Modifier.height(10.dp))
    Button(
        enabled = permissionGranted && declaresPermission,
        onClick = {
            viewModel.sendMessage("Use run_shell_command to execute: echo hello from termux")
        }
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Test shell now")
    }
}

@Composable
private fun TermuxStep(
    number: String,
    title: String,
    ok: Boolean?,
    body: @Composable () -> Unit
) {
    val accent = when (ok) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = accent.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.extraSmall
                ) {
                    Text(
                        number,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                val badge = when (ok) { true -> "OK"; false -> "missing"; null -> "check" }
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent
                )
            }
            Spacer(Modifier.height(6.dp))
            body()
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

@Composable
private fun TermuxCommandRow(step: String, command: String, context: Context) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$step $command",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.IconButton(onClick = {
                copyToClipboard(context, "termux-command", command)
                Toast.makeText(context, "Copied — paste it in Termux", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy command")
            }
        }
    }
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

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
