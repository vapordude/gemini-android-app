package com.gemini.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemini.app.ui.chat.ChatScreen
import com.gemini.app.ui.chat.ChatViewModel
import com.gemini.app.ui.login.LoginScreen
import com.gemini.bridge.RestGeminiCore
import com.gemini.ui.GeminiTheme

class MainActivity : ComponentActivity() {

    private val core by lazy { RestGeminiCore() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            GeminiTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val factory = remember {
                        object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                ChatViewModel(core) as T
                        }
                    }
                    val vm: ChatViewModel = viewModel(factory = factory)
                    val isReady by vm.isReady.collectAsState()

                    if (isReady) {
                        ChatScreen(vm)
                    } else {
                        LoginScreen(onLoginSuccess = { config ->
                            vm.initCore(config)
                        })
                    }
                }
            }
        }
    }
}
