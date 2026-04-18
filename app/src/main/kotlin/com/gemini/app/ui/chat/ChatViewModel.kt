package com.gemini.app.ui.chat

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gemini.bridge.RestGeminiCore
import com.gemini.domain.GeminiEvent
import com.gemini.domain.GeminiMessage
import com.gemini.domain.GeminiResult
import com.gemini.domain.MessageRole
import com.gemini.domain.ToolCall
import com.gemini.domain.ToolDecision
import com.gemini.domain.ToolSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(private val core: RestGeminiCore) : ViewModel() {

    private val _messages = mutableStateListOf<GeminiMessage>()
    val messages: List<GeminiMessage> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _pendingCall = MutableStateFlow<ToolCall?>(null)
    val pendingCall: StateFlow<ToolCall?> = _pendingCall.asStateFlow()

    private val _model = MutableStateFlow(core.currentModel())
    val model: StateFlow<String> = _model.asStateFlow()

    private val _autoApprove = MutableStateFlow(core.isAutoApprove())
    val autoApprove: StateFlow<Boolean> = _autoApprove.asStateFlow()

    private val _workspaceLabel = MutableStateFlow(core.workspace.rootLabel())
    val workspaceLabel: StateFlow<String> = _workspaceLabel.asStateFlow()

    val availableModels: List<String> = RestGeminiCore.AVAILABLE_MODELS
    val availableTools: List<ToolSpec> get() = core.availableTools()
    val termuxInstalled: Boolean get() = core.termux.isInstalled()

    init {
        viewModelScope.launch {
            core.events.collect { ev ->
                when (ev) {
                    is GeminiEvent.MessageAdded -> _messages.add(ev.message)
                    is GeminiEvent.ToolCallPending -> _pendingCall.value = ev.call
                    is GeminiEvent.ToolCallCompleted -> {
                        if (_pendingCall.value?.id == ev.result.callId) _pendingCall.value = null
                    }
                    is GeminiEvent.Notice -> _error.value = ev.message
                }
            }
        }
    }

    fun initCore(config: Map<String, Any>) {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = core.init(config)) {
                is GeminiResult.Success -> {
                    _isReady.value = true
                    _model.value = core.currentModel()
                }
                is GeminiResult.Error -> _error.value = result.message
            }
            _isLoading.value = false
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            when (val r = core.sendMessage(text)) {
                is GeminiResult.Success -> {}
                is GeminiResult.Error -> _error.value = r.message
            }
            _isLoading.value = false
        }
    }

    fun approve(callId: String, always: Boolean) {
        val decision = if (always) ToolDecision.AlwaysApprove else ToolDecision.Approve
        viewModelScope.launch {
            core.resolveToolDecision(callId, decision)
            if (always) _autoApprove.value = true
        }
    }

    fun reject(callId: String, reason: String = "user declined") {
        viewModelScope.launch {
            core.resolveToolDecision(callId, ToolDecision.Reject(reason))
        }
    }

    fun resetSession() {
        viewModelScope.launch {
            core.resetSession()
            _messages.clear()
        }
    }

    fun clearMessages() { _messages.clear() }

    fun setModel(model: String) {
        core.setModel(model)
        _model.value = core.currentModel()
    }

    fun setAutoApprove(enabled: Boolean) {
        core.setAutoApprove(enabled)
        _autoApprove.value = enabled
    }

    fun setProjectFolder(uri: String) {
        viewModelScope.launch {
            when (val r = core.setProjectFolder(uri)) {
                is GeminiResult.Error -> _error.value = r.message
                is GeminiResult.Success -> _workspaceLabel.value = core.workspace.rootLabel()
            }
        }
    }

    fun clearError() { _error.value = null }

    fun lastAssistantText(): String? =
        _messages.lastOrNull { it.role == MessageRole.MODEL && !it.text.isBlank() }?.text

    fun stats(): ChatStats {
        val userCount = _messages.count { it.role == MessageRole.USER }
        val modelCount = _messages.count { it.role == MessageRole.MODEL }
        val toolCount = _messages.count { it.role == MessageRole.TOOL }
        val chars = _messages.sumOf { it.text.length }
        return ChatStats(userCount, modelCount, toolCount, chars)
    }

    fun compressSession() {
        viewModelScope.launch {
            val snapshot = _messages.toList()
            if (snapshot.isEmpty()) return@launch
            val prompt = buildString {
                append("Summarise the following conversation. ")
                append("Keep the key decisions, open questions, and file changes. Output 10 bullets max.\n\n")
                snapshot.forEach { msg ->
                    when (msg.role) {
                        MessageRole.USER -> append("[user] ").append(msg.text).append('\n')
                        MessageRole.MODEL -> append("[assistant] ").append(msg.text).append('\n')
                        else -> Unit
                    }
                }
            }
            core.resetSession()
            _messages.clear()
            sendMessage(prompt)
        }
    }
}

data class ChatStats(
    val userMessages: Int,
    val modelMessages: Int,
    val toolEvents: Int,
    val totalChars: Int
)
