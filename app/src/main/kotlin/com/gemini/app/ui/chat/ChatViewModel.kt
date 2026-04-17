package com.gemini.app.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.domain.GeminiCore
import com.gemini.domain.GeminiMessage
import com.gemini.domain.GeminiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val geminiCore: GeminiCore) : ViewModel() {

    private val _messages = mutableStateListOf<GeminiMessage>()
    val messages: List<GeminiMessage> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun initCore(config: Map<String, Any>) {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = geminiCore.init(config)) {
                is GeminiResult.Success -> {
                    _isLoading.value = false
                }
                is GeminiResult.Error -> {
                    _error.value = result.message
                    _isLoading.value = false
                }
            }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Ajouter le message utilisateur immédiatement
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
        // Ici on mappe les commandes CLI vers des fonctions du Core
        when (command) {
            "reset" -> resetSession()
            "history" -> loadHistory()
            // Ajoutez d'autres commandes ici
        }
    }

    fun setProjectFolder(uri: String) {
        viewModelScope.launch {
            geminiCore.setProjectFolder(uri)
        }
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
