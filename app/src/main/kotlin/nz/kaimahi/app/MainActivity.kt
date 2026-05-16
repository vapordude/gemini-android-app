package nz.kaimahi.app

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
import nz.kaimahi.app.ui.chat.ChatScreen
import nz.kaimahi.app.ui.chat.ChatViewModel
import nz.kaimahi.app.ui.login.LoginScreen
import nz.kaimahi.app.ui.settings.ThemeMode
import nz.kaimahi.bridge.DriverMode
import nz.kaimahi.bridge.DriverRouter
import nz.kaimahi.bridge.NetworkInfo
import nz.kaimahi.bridge.RestGeminiCore
import nz.kaimahi.bridge.storage.SecurePrefs
import nz.kaimahi.localdriver.Gemma4LocalCore
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
                    val appContext = LocalContext.current.applicationContext

                    // Two driver instances + a SAFETY_FIRST router that picks
                    // between them per turn. RestGeminiCore is also the
                    // settings/persistence/workspace backend; Gemma4LocalCore
                    // is the on-device Rust driver (no-op when libgemma4.so
                    // isn't packaged in this build).
                    val core = remember {
                        val rest = RestGeminiCore(appContext)
                        val authService = nz.kaimahi.app.ui.login.GeminiCliAuthService(appContext)
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
