package nz.kaimahi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import nz.kaimahi.app.ui.about.AboutScreen
import nz.kaimahi.app.ui.chat.ChatScreen
import nz.kaimahi.app.ui.chat.ChatViewModel
import nz.kaimahi.app.ui.drawer.DrawerProject
import nz.kaimahi.app.ui.drawer.KaimahiDestination
import nz.kaimahi.app.ui.drawer.KaimahiDrawerContent
import nz.kaimahi.app.ui.dynamic.DailyTodoHost
import nz.kaimahi.app.ui.local.DeploymentConfigsScreen
import nz.kaimahi.app.ui.local.TraceViewerScreen
import nz.kaimahi.app.ui.login.LoginScreen
import nz.kaimahi.app.ui.memory.MemoryBrowserHost
import nz.kaimahi.app.ui.settings.ThemeMode
import nz.kaimahi.bridge.RestGeminiCore
import nz.kaimahi.domain.EmdashProfile
import nz.kaimahi.emdash.RustEmdashClient
import nz.kaimahi.ui.KaimahiTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            KaimahiTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KaimahiApp(themeMode = themeMode, onThemeChange = { themeMode = it })
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun KaimahiApp(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val core = remember { RestGeminiCore(appContext) }
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(core, appContext) as T
        }
    }
    val vm: ChatViewModel = viewModel(factory = factory)
    val isReady by vm.isReady.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val localTraces by vm.localTraceEvents.collectAsState()
    val context = LocalContext.current

    // Local-first bring-up.
    LaunchedEffect(Unit) {
        if (!isReady) {
            val localPath = vm.preselectedLocalModelPath()
            if (!localPath.isNullOrBlank()) {
                vm.enterLocalOnlyMode(localPath)
            } else if (vm.hasPersistedSession()) {
                vm.tryAutoLogin(context)
            }
        }
    }

    if (!isReady) {
        LoginScreen(
            onLoginSuccess = { config -> vm.initCore(config) },
            onLocalOnly = { vm.enterLocalOnlyMode(null) },
            isLoading = isLoading,
        )
        return
    }

    // ── Drawer-backed shell ─────────────────────────────────────────────
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var destination by remember { mutableStateOf(KaimahiDestination.Chat) }
    var activeProjectName by remember { mutableStateOf<String?>(null) }
    var projectsRefreshTrigger by remember { mutableStateOf(0) }
    val projects = remember(projectsRefreshTrigger) {
        // Read-only snapshot for the drawer; mutated through the
        // archive/unarchive/delete handlers below.
        (vm.listActiveChats() + vm.listArchivedChats()).map { e ->
            DrawerProject(
                name = e.name,
                displayName = e.name,
                whenLabel = relativeTimeLabel(e.updatedAt),
                messageCount = e.messageCount,
                archived = e.archived,
            )
        }
    }
    val archivedCount = projects.count { it.archived }

    val emdashClient = remember { RustEmdashClient() }
    var deploymentProfiles by remember { mutableStateOf<List<EmdashProfile>>(emptyList()) }
    var loadingDeployments by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DrawerProject?>(null) }

    fun refreshProjects() { projectsRefreshTrigger++ }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            KaimahiDrawerContent(
                activeDestination = destination,
                activeProject = activeProjectName,
                projects = projects,
                archivedCount = archivedCount,
                onDestinationSelected = { d ->
                    destination = d
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    vm.startNewChat()
                    activeProjectName = null
                    destination = KaimahiDestination.Chat
                    scope.launch { drawerState.close() }
                },
                onProjectSelected = { p ->
                    vm.resumeChat(p.name)
                    activeProjectName = p.name
                    destination = KaimahiDestination.Chat
                    scope.launch { drawerState.close() }
                },
                onArchiveProject = { p ->
                    vm.archiveChat(p.name); refreshProjects()
                },
                onUnarchiveProject = { p ->
                    vm.unarchiveChat(p.name); refreshProjects()
                },
                onRenameProject = { p -> renameTarget = p },
                onDeleteProject = { p ->
                    vm.deleteChat(p.name); refreshProjects()
                    if (activeProjectName == p.name) activeProjectName = null
                },
                activeModelLabel = if (vm.preselectedLocalModelPath() != null) "local" else "cloud",
            )
        },
    ) {
        val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
        when (destination) {
            KaimahiDestination.Chat -> ChatScreen(
                viewModel = vm,
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                onOpenLocalTraces = { destination = KaimahiDestination.TraceViewer },
                onOpenDeploymentConfigs = { destination = KaimahiDestination.Deployments },
            )
            KaimahiDestination.DailyTodo -> DailyTodoHost(onDrawerOpen = openDrawer)
            KaimahiDestination.MemoryBrowser -> MemoryBrowserHost(
                chatViewModel = vm,
                onDrawerOpen = openDrawer,
            )
            KaimahiDestination.TraceViewer -> TraceViewerScreen(
                events = localTraces,
                navigationIcon = {
                    IconButton(onClick = { destination = KaimahiDestination.Chat }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
            KaimahiDestination.Deployments -> {
                LaunchedEffect(destination) {
                    loadingDeployments = true
                    deploymentProfiles = runCatching { emdashClient.listProfiles() }
                        .getOrDefault(emptyList())
                    loadingDeployments = false
                }
                DeploymentConfigsScreen(
                    profiles = deploymentProfiles,
                    navigationIcon = {
                        IconButton(onClick = { destination = KaimahiDestination.Chat }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (loadingDeployments) {
                            androidx.compose.material3.Text("Loading…")
                        }
                    },
                )
            }
            // ModelPicker + Settings open the same chat screen for now — the
            // existing flows route through chat overflow / top-bar. A future
            // commit can promote them to standalone screens.
            KaimahiDestination.ModelPicker,
            KaimahiDestination.Settings -> ChatScreen(
                viewModel = vm,
                themeMode = themeMode,
                onThemeChange = onThemeChange,
                onOpenLocalTraces = { destination = KaimahiDestination.TraceViewer },
                onOpenDeploymentConfigs = { destination = KaimahiDestination.Deployments },
            )
            KaimahiDestination.About -> AboutScreen(
                onBack = { destination = KaimahiDestination.Chat },
            )
        }
    }

    val targetForRename = renameTarget
    if (targetForRename != null) {
        RenameProjectDialog(
            initial = targetForRename.displayName,
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                val ok = vm.renameChat(targetForRename.name, newName)
                if (ok && activeProjectName == targetForRename.name) {
                    activeProjectName = slugify(newName)
                }
                renameTarget = null
                refreshProjects()
            },
        )
    }
}

@androidx.compose.runtime.Composable
private fun RenameProjectDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Rename project") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { androidx.compose.material3.Text("New name") },
                singleLine = true,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (text.isNotBlank() && text.trim() != initial) onConfirm(text.trim()) },
                enabled = text.isNotBlank() && text.trim() != initial,
            ) { androidx.compose.material3.Text("Rename") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Cancel")
            }
        },
    )
}

/** Mirror of ChatStore.slug — keeps the active-project pointer in sync. */
private fun slugify(raw: String): String =
    raw.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").take(64)

private fun relativeTimeLabel(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val delta = (now - epochMs).coerceAtLeast(0)
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    val weeks = days / 7
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days == 1L -> "yesterday"
        days < 7 -> "${days}d ago"
        weeks < 5 -> "${weeks}w ago"
        else -> "long ago"
    }
}
