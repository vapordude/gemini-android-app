package nz.kaimahi.app.ui.chat

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import nz.kaimahi.app.ui.local.InferenceMode
import nz.kaimahi.bridge.LocalModelFile
import nz.kaimahi.bridge.RestGeminiCore
import nz.kaimahi.domain.AgentMarker
import nz.kaimahi.domain.AgentMarkerStatus
import nz.kaimahi.domain.Attachment
import nz.kaimahi.domain.GeminiEvent
import nz.kaimahi.domain.GeminiMessage
import nz.kaimahi.domain.GeminiResult
import nz.kaimahi.domain.GenerateRequest
import nz.kaimahi.domain.MessageRole
import nz.kaimahi.domain.TraceEvent
import nz.kaimahi.domain.ToolCall
import nz.kaimahi.domain.ToolDecision
import nz.kaimahi.domain.ToolSpec
import nz.kaimahi.inference.RustInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel(
    private val core: RestGeminiCore,
    private val appContext: Context,
) : ViewModel() {
    private companion object {
        private const val TAG = "ChatViewModel"
        private const val MILLIS_PER_SECOND = 1000.0
        private const val MIN_DURATION_MS = 1L
    }

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

    private val _workspacePath = MutableStateFlow(core.workspace.absolutePath())
    val workspacePath: StateFlow<String?> = _workspacePath.asStateFlow()

    private val _workspaceReason = MutableStateFlow(core.workspace.unreachableReason())
    val workspaceReason: StateFlow<String?> = _workspaceReason.asStateFlow()

    private val _workspaceUri = MutableStateFlow(core.workspace.rootUri()?.toString())
    val workspaceUri: StateFlow<String?> = _workspaceUri.asStateFlow()

    private val _availableModels = MutableStateFlow(core.listModels())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _imagenModel = MutableStateFlow(core.imagenModel())
    val imagenModel: StateFlow<String> = _imagenModel.asStateFlow()

    private val _localModels = MutableStateFlow<List<LocalModelFile>>(emptyList())
    val localModels: StateFlow<List<LocalModelFile>> = _localModels.asStateFlow()

    private val _selectedLocalModelPath = MutableStateFlow(core.selectedLocalModelPath())
    val selectedLocalModelPath: StateFlow<String?> = _selectedLocalModelPath.asStateFlow()

    private val _inferenceMode = MutableStateFlow(InferenceMode.CLOUD_GEMINI)
    val inferenceMode: StateFlow<InferenceMode> = _inferenceMode.asStateFlow()

    private val _thinking = MutableStateFlow<String?>(null)
    val thinking: StateFlow<String?> = _thinking.asStateFlow()

    /**
     * Live typed agent activity. Each entry is one piece of work the
     * agent is doing or has just done — reading a file, grepping,
     * editing, running a shell command. The UI renders the list as
     * expandable rows so the user can tap any one to see the detail.
     * Cleared on `startNewChat`.
     */
    private val _agentMarkers = MutableStateFlow<List<AgentMarker>>(emptyList())
    val agentMarkers: StateFlow<List<AgentMarker>> = _agentMarkers.asStateFlow()

    private val _tokenUsage = MutableStateFlow(
        TokenUsageState(core.currentTokenUsage().first, core.currentTokenUsage().second)
    )
    val tokenUsage: StateFlow<TokenUsageState> = _tokenUsage.asStateFlow()

    private val _autoCompressEnabled = MutableStateFlow(core.isAutoCompressEnabled())
    val autoCompressEnabled: StateFlow<Boolean> = _autoCompressEnabled.asStateFlow()

    private val _autoCompressThreshold = MutableStateFlow(core.autoCompressThreshold())
    val autoCompressThreshold: StateFlow<Float> = _autoCompressThreshold.asStateFlow()

    private val _compressing = MutableStateFlow(false)
    val compressing: StateFlow<Boolean> = _compressing.asStateFlow()

    private val _autoSaveEnabled = MutableStateFlow(core.isAutoSaveEnabled())
    val autoSaveEnabled: StateFlow<Boolean> = _autoSaveEnabled.asStateFlow()

    private val _pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())
    val pendingAttachments: StateFlow<List<PendingAttachment>> = _pendingAttachments.asStateFlow()

    private val _localTraceEvents = MutableStateFlow<List<TraceEvent>>(emptyList())
    val localTraceEvents: StateFlow<List<TraceEvent>> = _localTraceEvents.asStateFlow()

    private var sendJob: Job? = null
    private var lastUserPrompt: String? = null
    private val localInference by lazy { RustInferenceEngine(appContext) }
    private var localLoadedModelPath: String? = null

    val availableTools: List<ToolSpec> get() = core.availableTools()
    val termuxInstalled: Boolean get() = core.termux.isInstalled()

    fun isTermuxGuideShown(): Boolean = core.isTermuxGuideShown()
    fun markTermuxGuideShown() = core.markTermuxGuideShown()

    suspend fun testTermuxShell(): String {
        val r = core.termux.run("echo hello from termux && uname -a")
        return if (r.ok) r.stdout.ifBlank { "ok" }
        else buildString {
            append("exit=").append(r.exitCode)
            if (r.stderr.isNotBlank()) append('\n').append(r.stderr)
        }
    }

    suspend fun checkGeminiCliAuthInTermux(): String {
        val command = """
            if [ -d "${'$'}HOME/.gemini" ]; then
              echo "FOUND:${'$'}HOME/.gemini"
              ls -la "${'$'}HOME/.gemini"
            else
              echo "MISSING:${'$'}HOME/.gemini"
            fi
        """.trimIndent()
        val r = core.termux.run(command)
        return if (r.ok) r.stdout.ifBlank { "ok" }
        else buildString {
            append("exit=").append(r.exitCode)
            if (r.stderr.isNotBlank()) append('\n').append(r.stderr)
        }
    }

    init {
        viewModelScope.launch {
            core.events.collect { ev ->
                when (ev) {
                    is GeminiEvent.MessageAdded -> _messages.add(ev.message)
                    is GeminiEvent.MessageUpdated -> {
                        val idx = _messages.indexOfLast { it.id == ev.message.id }
                        if (idx >= 0) _messages[idx] = ev.message else _messages.add(ev.message)
                    }
                    is GeminiEvent.ToolCallPending -> _pendingCall.value = ev.call
                    is GeminiEvent.ToolCallCompleted -> {
                        if (_pendingCall.value?.id == ev.result.callId) _pendingCall.value = null
                    }
                    is GeminiEvent.Thinking -> _thinking.value = ev.label
                    is GeminiEvent.Notice -> _error.value = ev.message
                    is GeminiEvent.TokenUsage ->
                        _tokenUsage.value = TokenUsageState(ev.total, ev.limit)
                    is GeminiEvent.MarkerUpserted -> {
                        // Upsert by id. New markers append, status changes
                        // replace in place so the UI doesn't flicker the
                        // row out and back in when a Running marker
                        // transitions to Done.
                        val current = _agentMarkers.value
                        val idx = current.indexOfFirst { it.id == ev.marker.id }
                        _agentMarkers.value = if (idx >= 0) {
                            current.toMutableList().apply { this[idx] = ev.marker }
                        } else {
                            current + ev.marker
                        }
                    }
                    is GeminiEvent.MarkerCleared -> {
                        _agentMarkers.value = _agentMarkers.value.filterNot { it.id == ev.id }
                    }
                }
            }
        }
        viewModelScope.launch { refreshLocalModelsNow() }
    }

    fun initCore(config: Map<String, Any>) {
        viewModelScope.launch {
            _isLoading.value = true
            when (val result = core.init(config)) {
                is GeminiResult.Success -> {
                    _isReady.value = true
                    _model.value = core.currentModel()
                    _availableModels.value = core.listModels()
                    // Resume the previous conversation if autosave is on and the
                    // in-memory session is still empty.
                    if (core.isAutoSaveEnabled() && _messages.isEmpty()) {
                        core.resumeCurrentSession()
                    }
                }
                is GeminiResult.Error -> _error.value = result.message
            }
            _isLoading.value = false
        }
    }

    fun setAutoSaveEnabled(enabled: Boolean) {
        core.setAutoSaveEnabled(enabled)
        _autoSaveEnabled.value = enabled
    }

    fun refreshModels() {
        viewModelScope.launch {
            _availableModels.value = core.refreshModels()
        }
    }

    fun signOut() {
        viewModelScope.launch {
            core.signOut()
            _messages.clear()
            _pendingAttachments.value = emptyList()
            _model.value = core.currentModel()
            _availableModels.value = core.listModels()
            _isReady.value = false
        }
    }

    fun hasPersistedSession(): Boolean = core.hasPersistedSession()

    /**
     * Path of a GGUF the user has previously imported and chosen as the
     * default local model. Used by MainActivity to skip the login screen
     * on cold start when local-only mode is viable.
     */
    fun preselectedLocalModelPath(): String? = core.selectedLocalModelPath()

    /**
     * Skip cloud auth entirely. The chat screen comes up immediately in
     * local-agent mode; if [path] is non-null, also pin it as the
     * selected local model. Used by both the "Skip — local model only"
     * button on the login screen and the auto-bring-up path that fires
     * when a local model was already picked.
     */
    fun enterLocalOnlyMode(path: String?) {
        if (!path.isNullOrBlank()) {
            core.setSelectedLocalModelPath(path)
            _selectedLocalModelPath.value = path
        }
        core.markLocalOnly()
        _inferenceMode.value = InferenceMode.LOCAL_AGENT
        _isReady.value = true
    }

    fun tryAutoLogin(context: android.content.Context) {
        val savedApi = core.persistedApiKey()
        val savedToken = core.persistedAccessToken()
        val savedRefresh = core.persistedRefreshToken()
        val savedUseCodeAssist = core.persistedUseCodeAssist()

        when {
            !savedApi.isNullOrBlank() ->
                initCore(mapOf("api_key" to savedApi, "remember" to true))

            // Gemini-CLI / Code Assist resume: we have a refresh_token
            // and the prefs say to use cloudcode-pa. The CodeAssistSession
            // will refresh the access token itself if it's expired.
            savedUseCodeAssist && !savedRefresh.isNullOrBlank() ->
                initCore(
                    mapOf(
                        "access_token" to (savedToken.orEmpty()),
                        "refresh_token" to savedRefresh,
                        "remember" to true,
                    )
                )

            !savedToken.isNullOrBlank() -> viewModelScope.launch {
                val authService = nz.kaimahi.app.ui.login.GoogleAuthService(context)
                val account = authService.getLastSignedInAccount()
                if (account != null) {
                    val freshToken = authService.getAccessToken(account)
                    if (freshToken != null) {
                        initCore(mapOf("access_token" to freshToken, "remember" to true))
                    } else {
                        // Stale; let init() fail and bounce to the login screen.
                        initCore(mapOf("access_token" to savedToken, "remember" to true))
                    }
                } else {
                    initCore(mapOf("access_token" to savedToken, "remember" to true))
                }
            }
        }
    }

    // --- chat persistence ---
    fun listSavedChats(): List<nz.kaimahi.bridge.storage.ChatStore.Entry> = core.listChats()
    fun listActiveChats(): List<nz.kaimahi.bridge.storage.ChatStore.Entry> = core.listActiveChats()
    fun listArchivedChats(): List<nz.kaimahi.bridge.storage.ChatStore.Entry> = core.listArchivedChats()
    fun saveChat(name: String) { core.saveChat(name) }
    fun deleteChat(name: String) { core.deleteChat(name) }
    fun archiveChat(name: String): Boolean = core.archiveChat(name)
    fun unarchiveChat(name: String): Boolean = core.unarchiveChat(name)
    fun renameChat(oldName: String, newName: String): Boolean =
        core.renameChat(oldName, newName)
    fun resumeChat(name: String) {
        viewModelScope.launch {
            _messages.clear()
            val ok = core.resumeChat(name)
            if (!ok) _error.value = "Could not resume \"$name\""
        }
    }
    /** Clear the UI message list and any transient turn state. Underlying core
     *  state is reset on next send. Drops a stale pending tool-approval card,
     *  in-flight thinking label, and pending attachments so the new chat
     *  doesn't inherit ghosts from the previous turn. */
    fun startNewChat() {
        sendJob?.cancel()
        sendJob = null
        _messages.clear()
        _error.value = null
        _pendingCall.value = null
        _thinking.value = null
        _pendingAttachments.value = emptyList()
        _agentMarkers.value = emptyList()
    }

    fun sendMessage(text: String) {
        val attachments = _pendingAttachments.value
        if (text.isBlank() && attachments.isEmpty()) return
        if (sendJob?.isActive == true) return
        if (_inferenceMode.value == InferenceMode.LOCAL_AGENT) {
            sendLocalMessage(text, attachments)
            return
        }
        lastUserPrompt = text
        val payload = attachments.map { Attachment(it.bytes, it.mimeType, it.localPath) }
        _pendingAttachments.value = emptyList()
        sendJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val result = if (payload.isEmpty()) core.sendMessage(text)
                    else core.sendMessage(text, payload)
                when (result) {
                    is GeminiResult.Success -> {}
                    is GeminiResult.Error -> _error.value = result.message
                }
            } catch (_: CancellationException) {
                // Cancelled by user — silent.
            } finally {
                _isLoading.value = false
                _thinking.value = null
                sendJob = null
                maybeAutoCompress()
            }
        }
    }

    private fun sendLocalMessage(text: String, attachments: List<PendingAttachment>) {
        if (attachments.isNotEmpty()) {
            _error.value = "Local mode does not support attachments yet"
            return
        }
        val modelPath = _selectedLocalModelPath.value
        if (modelPath.isNullOrBlank()) {
            _error.value = "Select a local GGUF model first in Settings → Local model (GGUF)."
            return
        }
        lastUserPrompt = text
        _pendingAttachments.value = emptyList()
        sendJob = viewModelScope.launch {
            _isLoading.value = true
            try {
                val inference = runCatching { localInference }.getOrElse {
                    throw IllegalStateException(
                        "Failed to initialize local inference engine. Check native runtime libraries.",
                        it
                    )
                }
                _messages.add(
                    GeminiMessage(
                        id = "local-user-${System.nanoTime()}",
                        text = text,
                        isUser = true,
                        timestamp = System.currentTimeMillis(),
                        role = MessageRole.USER
                    )
                )
                if (localLoadedModelPath != modelPath) {
                    val loaded = inference.loadModel(modelPath).getOrElse {
                        throw IllegalStateException(
                            "Could not load local model: ${it.message ?: "unexpected runtime error"}"
                        )
                    }
                    localLoadedModelPath = loaded.path
                    val runtime = runCatching { inference.info() }
                        .onFailure { Log.w(TAG, "Could not read local runtime info", it) }
                        .getOrNull()
                    _localTraceEvents.value = _localTraceEvents.value + TraceEvent.ModelLoaded(
                        timestampMs = System.currentTimeMillis(),
                        archTag = loaded.archTag.ifBlank { "unknown" },
                        isa = runtime?.isa ?: "unknown",
                        threads = runtime?.threads ?: Runtime.getRuntime().availableProcessors()
                    )
                }
                val modelMessageId = "local-model-${System.nanoTime()}"
                _messages.add(
                    GeminiMessage(
                        id = modelMessageId,
                        text = "",
                        isUser = false,
                        timestamp = System.currentTimeMillis(),
                        role = MessageRole.MODEL
                    )
                )
                var output = ""
                var tokenCount = 0
                val startedAt = System.currentTimeMillis()

                val loop = nz.kaimahi.bridge.agent.LocalAgentLoop(
                    tools = core.localToolSpecs(),
                    streamer = nz.kaimahi.bridge.agent.LocalAgentLoop.TokenStreamer { prompt ->
                        kotlinx.coroutines.flow.flow {
                            inference.generate(GenerateRequest(prompt = prompt)).collect { token ->
                                if (token.text.isNotEmpty()) {
                                    tokenCount++
                                    emit(token.text)
                                }
                            }
                        }
                    },
                    runner = nz.kaimahi.bridge.agent.LocalAgentLoop.ToolRunner { call ->
                        core.runLocalAgentTool(call)
                    },
                    workspaceLabel = core.workspaceLabel(),
                    modelName = localLoadedModelPath?.substringAfterLast('/'),
                )
                loop.run(
                    userMessage = text,
                    sink = nz.kaimahi.bridge.agent.LocalAgentLoop.Sink(
                        onAssistantText = { delta ->
                            output += delta
                            val idx = _messages.indexOfLast { it.id == modelMessageId }
                            if (idx >= 0) _messages[idx] = _messages[idx].copy(text = output)
                        },
                        onIterationLimit = {
                            output += "\n\n_(reached the agent's iteration limit — ask me to continue if you want me to keep going)_"
                            val idx = _messages.indexOfLast { it.id == modelMessageId }
                            if (idx >= 0) _messages[idx] = _messages[idx].copy(text = output)
                        },
                        onTruncated = {
                            output += "\n\n_(stream cut off mid-tool-call — that one's on me, try again?)_"
                            val idx = _messages.indexOfLast { it.id == modelMessageId }
                            if (idx >= 0) _messages[idx] = _messages[idx].copy(text = output)
                        },
                    ),
                )

                if (output.isBlank()) {
                    val idx = _messages.indexOfLast { it.id == modelMessageId }
                    if (idx >= 0) _messages.removeAt(idx)
                    _error.value = "No response generated. Verify the model is compatible or select a different GGUF model."
                } else {
                    val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(MIN_DURATION_MS)
                    _localTraceEvents.value = _localTraceEvents.value + TraceEvent.GenerateFinished(
                        timestampMs = System.currentTimeMillis(),
                        tokens = tokenCount,
                        durationMs = durationMs,
                        tokensPerSec = (tokenCount.toDouble() / durationMs) * MILLIS_PER_SECOND
                    )
                }
            } catch (_: CancellationException) {
                // Cancelled by user — silent.
            } catch (t: Throwable) {
                _error.value = t.message ?: "Local inference failed"
                _localTraceEvents.value = _localTraceEvents.value + TraceEvent.Error(
                    timestampMs = System.currentTimeMillis(),
                    kind = "local_inference",
                    message = _error.value ?: "Local inference failed"
                )
            } finally {
                _isLoading.value = false
                _thinking.value = null
                sendJob = null
            }
        }
    }

    fun attachImageFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { readAttachment(context, uri) }
            if (loaded == null) {
                _error.value = "Could not read the selected image"
                return@launch
            }
            if (loaded.bytes.size > MAX_ATTACHMENT_BYTES) {
                _error.value = "Image too large (max 15 MB)"
                return@launch
            }
            _pendingAttachments.value = _pendingAttachments.value + loaded
        }
    }

    fun removeAttachment(id: String) {
        _pendingAttachments.value = _pendingAttachments.value.filterNot { it.id == id }
    }

    fun clearAttachments() { _pendingAttachments.value = emptyList() }

    private fun readAttachment(context: Context, uri: Uri): PendingAttachment? {
        return runCatching {
            val mime = context.contentResolver.getType(uri) ?: "image/*"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            val displayName = queryDisplayName(context, uri)
                ?: "image.${mime.substringAfter('/').take(4)}"
            val id = "att-${System.nanoTime()}"
            val localPath = persistAttachment(context, id, bytes, mime)
            PendingAttachment(
                id = id,
                bytes = bytes,
                mimeType = mime,
                displayName = displayName.take(40),
                sizeBytes = bytes.size,
                localPath = localPath
            )
        }.getOrNull()
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    // Copy the picked image into app-owned cache so the chat bubble can show a
    // thumbnail without holding an Android content:// permission that may be
    // revoked, and so reloads from ChatStore can still find the file.
    private fun persistAttachment(
        context: Context,
        id: String,
        bytes: ByteArray,
        mime: String
    ): String? = runCatching {
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            mime.contains("heic") -> "heic"
            mime.contains("heif") -> "heif"
            else -> "jpg"
        }
        val dir = java.io.File(context.filesDir, "attachments").also { it.mkdirs() }
        val file = java.io.File(dir, "$id.$ext")
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()

    private fun maybeAutoCompress() {
        if (!_autoCompressEnabled.value) return
        if (_compressing.value) return
        val (total, limit) = _tokenUsage.value.let { it.total to it.limit }
        if (limit == null || limit <= 0 || total <= 0) return
        val ratio = total.toFloat() / limit.toFloat()
        if (ratio < _autoCompressThreshold.value) return
        compressSession(auto = true)
    }

    fun cancelSend() {
        sendJob?.cancel()
        sendJob = null
        _isLoading.value = false
        _thinking.value = null
        _pendingCall.value = null
    }

    fun regenerateLast() {
        val prompt = lastUserPrompt ?: return
        if (sendJob?.isActive == true) return
        // Drop the last model/tool bubbles so the re-run looks fresh.
        while (_messages.isNotEmpty() && _messages.last().role != MessageRole.USER) {
            _messages.removeAt(_messages.size - 1)
        }
        if (_messages.isNotEmpty() && _messages.last().role == MessageRole.USER) {
            _messages.removeAt(_messages.size - 1)
        }
        sendMessage(prompt)
    }

    fun approve(callId: String, always: Boolean) {
        val decision = if (always) ToolDecision.AlwaysApprove else ToolDecision.Approve
        _pendingCall.value = null
        viewModelScope.launch {
            core.resolveToolDecision(callId, decision)
            if (always) _autoApprove.value = true
        }
    }

    fun reject(callId: String, reason: String = "user declined") {
        _pendingCall.value = null
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

    fun setInferenceMode(mode: InferenceMode) {
        _inferenceMode.value = mode
    }

    fun setImagenModel(name: String) {
        core.setImagenModel(name)
        _imagenModel.value = core.imagenModel()
    }

    fun setAutoApprove(enabled: Boolean) {
        core.setAutoApprove(enabled)
        _autoApprove.value = enabled
    }

    fun setProjectFolder(uri: String) {
        viewModelScope.launch {
            when (val r = core.setProjectFolder(uri)) {
                is GeminiResult.Error -> _error.value = r.message
                is GeminiResult.Success -> {
                    _workspaceLabel.value = core.workspace.rootLabel()
                    _workspacePath.value = core.workspace.absolutePath()
                    _workspaceReason.value = core.workspace.unreachableReason()
                    _workspaceUri.value = core.workspace.rootUri()?.toString()
                }
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

    fun compressSession(auto: Boolean = false) {
        if (_compressing.value) return
        viewModelScope.launch {
            val snapshot = _messages.toList()
            if (snapshot.isEmpty()) return@launch
            _compressing.value = true
            try {
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
                // Inline call instead of sendMessage() to avoid re-entering
                // auto-compress on the summary response.
                _isLoading.value = true
                try {
                    when (val r = core.sendMessage(prompt)) {
                        is GeminiResult.Success -> {}
                        is GeminiResult.Error -> _error.value = r.message
                    }
                } catch (_: CancellationException) {
                    // silent
                } finally {
                    _isLoading.value = false
                    _thinking.value = null
                }
            } finally {
                _compressing.value = false
            }
        }
    }

    fun setAutoCompressEnabled(enabled: Boolean) {
        core.setAutoCompressEnabled(enabled)
        _autoCompressEnabled.value = enabled
    }

    fun setAutoCompressThreshold(fraction: Float) {
        core.setAutoCompressThreshold(fraction)
        _autoCompressThreshold.value = core.autoCompressThreshold()
    }

    fun refreshLocalModels() {
        viewModelScope.launch { refreshLocalModelsNow() }
    }

    fun selectLocalModel(path: String?) {
        val selected = path?.takeIf { it.isNotBlank() }
        core.setSelectedLocalModelPath(selected)
        _selectedLocalModelPath.value = core.selectedLocalModelPath()
        if (selected == null || selected != localLoadedModelPath) {
            localLoadedModelPath = null
        }
    }

    fun importLocalModel(uri: Uri) {
        viewModelScope.launch {
            core.importLocalModel(uri)
                .onSuccess {
                    refreshLocalModelsNow()
                }
                .onFailure {
                    _error.value = it.message ?: "Could not import model file"
                }
        }
    }

    fun deleteLocalModel(path: String) {
        viewModelScope.launch {
            val deleted = core.removeLocalModel(path)
            if (!deleted) {
                _error.value = "Could not delete model file"
                return@launch
            }
            refreshLocalModelsNow()
        }
    }

    private suspend fun refreshLocalModelsNow() {
        _localModels.value = core.listLocalModels()
        val selected = core.selectedLocalModelPath()
        _selectedLocalModelPath.value = selected
        if (!selected.isNullOrBlank() && _localModels.value.none { it.path == selected }) {
            core.setSelectedLocalModelPath(null)
            _selectedLocalModelPath.value = null
        }
    }
}

private const val MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024

data class PendingAttachment(
    val id: String,
    val bytes: ByteArray,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Int,
    val localPath: String? = null
) {
    override fun equals(other: Any?) = other is PendingAttachment && id == other.id
    override fun hashCode() = id.hashCode()
}

data class TokenUsageState(val total: Int, val limit: Int?)

fun ChatViewModel.exportAsMarkdown(): String {
    val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        .format(java.util.Date())
    val sb = StringBuilder()
    sb.append("# Kaimahi conversation\n\n")
    sb.append("_Model: ").append(model.value).append("_  \n")
    sb.append("_Exported: ").append(ts).append("_\n\n")
    messages.forEach { msg ->
        when (msg.role) {
            MessageRole.USER -> {
                sb.append("## User\n\n").append(msg.text.trim()).append("\n\n")
            }
            MessageRole.MODEL -> {
                sb.append("## Kaimahi\n\n").append(msg.text.trim()).append("\n\n")
            }
            MessageRole.TOOL -> {
                msg.toolCall?.let { call ->
                    sb.append("### Tool · ").append(call.name).append("\n\n")
                    call.arguments.forEach { (k, v) ->
                        val s = v?.toString().orEmpty().take(400)
                        sb.append("- **").append(k).append("**: `")
                            .append(s.replace("`", "\\`")).append("`\n")
                    }
                    sb.append('\n')
                }
                msg.toolResult?.let { res ->
                    val status = if (res.ok) "✅" else "❌"
                    sb.append("**Result** ").append(status).append("\n\n")
                    sb.append("```\n").append(res.output.trim()).append("\n```\n\n")
                }
            }
            MessageRole.SYSTEM -> Unit
        }
    }
    return sb.toString()
}

data class ChatStats(
    val userMessages: Int,
    val modelMessages: Int,
    val toolEvents: Int,
    val totalChars: Int
)
