package com.gemini.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.gemini.app.ui.chat.ChatScreen
import com.gemini.app.ui.chat.ChatViewModel
import com.gemini.bridge.QuickJSGeminiCore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val core = QuickJSGeminiCore(this)
        val viewModel = ChatViewModel(core)
        setContent {
            ChatScreen(viewModel)
        }
    }
}