package com.gemini.app.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.domain.GeminiCore
import com.gemini.domain.GeminiMessage
import com.gemini.domain.GeminiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val geminiCore: GeminiCore) : ViewModel() {

    private val _messages = mutableStateListOf<GeminiMessage>()
    val messages: List<GeminiMessage> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun initCore(config: Map<String, Any>) {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = geminiCore.init(config)) {
                is GeminiResult.Success -> {
                    _isReady.value = true
                }
                is GeminiResult.Error -> {
                    _error.value = result.message
                }
            }
            _isLoading.value = false
        }
    }

    fun setProjectFolder(uri: String) {
        viewModelScope.launch {
            when (val r = geminiCore.setProjectFolder(uri)) {
                is GeminiResult.Error -> _error.value = r.message
                is GeminiResult.Success -> {}
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        val userMsg = GeminiMessage(
            id = System.currentTimeMillis().toString(),
            text = text,
            isUser = true,
            timestamp = System.currentTimeMillis()
        )
        _messages.add(userMsg)

        viewModelScope.launch {
            _isLoading.value = true
            when (val result = geminiCore.sendMessage(text)) {
                is GeminiResult.Success -> {
                    val aiMsg = GeminiMessage(
                        id = (System.currentTimeMillis() + 1).toString(),
                        text = result.response,
                        isUser = false,
                        timestamp = System.currentTimeMillis()
                    )
                    _messages.add(aiMsg)
                }
                is GeminiResult.Error -> {
                    _error.value = result.message
                }
            }
            _isLoading.value = false
        }
    }

    fun execCommand(command: String) {
        when (command) {
            "reset" -> resetSession()
            "history" -> loadHistory()
            "clear" -> _messages.clear()
            else -> {
                // Commandes non encore câblées côté core — on les injecte
                // comme message utilisateur pour feedback visuel.
                sendMessage("/$command")
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun resetSession() {
        viewModelScope.launch {
            geminiCore.resetSession()
            _messages.clear()
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            val history = geminiCore.loadHistory()
            _messages.clear()
            _messages.addAll(history)
        }
    }
}
