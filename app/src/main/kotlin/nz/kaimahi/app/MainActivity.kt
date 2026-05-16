package nz.kaimahi.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import nz.kaimahi.app.ui.chat.ChatScreen
import nz.kaimahi.app.ui.chat.ChatViewModel
import nz.kaimahi.app.ui.local.DeploymentConfigsScreen
import nz.kaimahi.app.ui.local.TraceViewerScreen
import nz.kaimahi.app.ui.login.LoginScreen
import nz.kaimahi.app.ui.settings.ThemeMode
import nz.kaimahi.bridge.RestGeminiCore
import nz.kaimahi.emdash.RustEmdashClient
import nz.kaimahi.domain.EmdashProfile
import nz.kaimahi.ui.KaimahiTheme

private enum class AppPane { CHAT, TRACE_VIEWER, DEPLOYMENTS }

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
                    var pane by remember { mutableStateOf(AppPane.CHAT) }
                    val emdashClient = remember { RustEmdashClient() }
                    var deploymentProfiles by remember { mutableStateOf<List<EmdashProfile>>(emptyList()) }
                    var loadingDeployments by remember { mutableStateOf(false) }

                    val context = LocalContext.current
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (!isReady && vm.hasPersistedSession()) vm.tryAutoLogin(context)
                    }
                    LaunchedEffect(isReady) {
                        if (!isReady) pane = AppPane.CHAT
                    }

                    if (!isReady) {
                        LoginScreen(
                            onLoginSuccess = { config -> vm.initCore(config) },
                            isLoading = isLoading
                        )
                    } else {
                        when (pane) {
                            AppPane.CHAT -> ChatScreen(
                                viewModel = vm,
                                themeMode = themeMode,
                                onThemeChange = { themeMode = it },
                                onOpenLocalTraces = { pane = AppPane.TRACE_VIEWER },
                                onOpenDeploymentConfigs = { pane = AppPane.DEPLOYMENTS }
                            )
                            AppPane.TRACE_VIEWER -> TraceViewerScreen(
                                events = localTraces,
                                navigationIcon = {
                                    IconButton(onClick = { pane = AppPane.CHAT }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            )
                            AppPane.DEPLOYMENTS -> {
                                LaunchedEffect(Unit) {
                                    loadingDeployments = true
                                    deploymentProfiles = runCatching { emdashClient.listProfiles() }
                                        .getOrDefault(emptyList())
                                    loadingDeployments = false
                                }
                                DeploymentConfigsScreen(
                                    profiles = deploymentProfiles,
                                    navigationIcon = {
                                        IconButton(onClick = { pane = AppPane.CHAT }) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                        }
                                    },
                                    actions = {
                                        if (loadingDeployments) {
                                            androidx.compose.material3.Text("Loading…")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
