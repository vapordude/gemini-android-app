package com.gemini.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.gemini.app.ui.chat.ChatScreen
import com.gemini.app.ui.chat.ChatViewModel
import com.gemini.app.ui.login.LoginScreen
import com.gemini.app.ui.settings.ThemeMode
import com.gemini.bridge.DriverMode
import com.gemini.bridge.DriverRouter
import com.gemini.bridge.NetworkInfo
import com.gemini.bridge.RestGeminiCore
import com.gemini.bridge.storage.SecurePrefs
import com.gemini.localdriver.Gemma4LocalCore
import com.gemini.ui.GeminiTheme

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
            GeminiTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val appContext = LocalContext.current.applicationContext

                    // Two driver instances + a SAFETY_FIRST router that picks
                    // between them per turn. RestGeminiCore is also the
                    // settings/persistence/workspace backend; Gemma4LocalCore
                    // is the on-device Rust driver (no-op when libgemma4.so
                    // isn't packaged in this build).
                    val core = remember {
                        val rest = RestGeminiCore(appContext)
                        val authService = com.gemini.app.ui.login.GeminiCliAuthService(appContext)
                        rest.onTokenRefreshRequested = refresh@{
                            val saved = rest.persistedOAuthTokens() ?: return@refresh null
                            val refreshed = authService.refresh(saved) ?: return@refresh null
                            SecurePrefs(appContext).oauthTokens = refreshed
                            refreshed
                        }
                        rest
                    }
                    val local = remember { Gemma4LocalCore() }
                    val prefs = remember { SecurePrefs(appContext) }
                    val router = remember {
                        DriverRouter(
                            local = local,
                            remote = core,
                            mode = { DriverMode.parse(prefs.driverMode) },
                            connectivity = { NetworkInfo.isOnline(appContext) },
                            isLocalReady = { local.isReady() },
                        )
                    }

                    val factory = remember {
                        object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                ChatViewModel(core, messaging = router) as T
                        }
                    }
                    val vm: ChatViewModel = viewModel(factory = factory)
                    val isReady by vm.isReady.collectAsState()
                    val isLoading by vm.isLoading.collectAsState()

                    val context = LocalContext.current
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (!isReady && vm.hasPersistedSession()) vm.tryAutoLogin(context)
                    }

                    if (isReady) ChatScreen(
                        viewModel = vm,
                        themeMode = themeMode,
                        onThemeChange = { themeMode = it }
                    )
                    else LoginScreen(
                        onLoginSuccess = { config -> vm.initCore(config) },
                        isLoading = isLoading
                    )
                }
            }
        }
    }
}
