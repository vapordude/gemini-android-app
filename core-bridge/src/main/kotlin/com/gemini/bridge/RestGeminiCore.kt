package com.gemini.bridge

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.gemini.bridge.termux.TermuxBridge
import com.gemini.bridge.tools.DeleteFileTool
import com.gemini.bridge.tools.EditFileTool
import com.gemini.bridge.tools.GenerateImageTool
import com.gemini.bridge.tools.GlobTool
import com.gemini.bridge.tools.GrepTool
import com.gemini.bridge.tools.ListDirTool
import com.gemini.bridge.tools.ReadFileTool
import com.gemini.bridge.tools.RunShellCommandTool
import com.gemini.bridge.tools.Tool
import com.gemini.bridge.tools.ToolRegistry
import com.gemini.bridge.tools.WriteFileTool
import com.gemini.bridge.storage.ChatStore
import com.gemini.bridge.storage.SecurePrefs
import com.gemini.bridge.workspace.Workspace
import com.gemini.domain.Attachment
import com.gemini.domain.GeminiCore
import com.gemini.domain.GeminiEvent
import com.gemini.domain.GeminiMessage
import com.gemini.domain.GeminiResult
import com.gemini.domain.MessageRole
import com.gemini.domain.ToolCall
import com.gemini.domain.ToolCallResult
import com.gemini.domain.ToolDecision
import com.gemini.domain.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Gemini client with native function calling. The model declares what it
 * wants to do (read a file, run a shell command, …) and we execute the
 * request locally using the tools in the registry. Destructive tools require
 * a UI-driven approval through [resolveToolDecision].
 */
class RestGeminiCore(
    appContext: Context,
    val workspace: Workspace = Workspace(appContext),
    val termux: TermuxBridge = TermuxBridge(appContext),
    private val prefs: SecurePrefs = SecurePrefs(appContext),
    private val chatStore: ChatStore = ChatStore(appContext),
    val memory: com.gemini.bridge.memory.MemoryStore = com.gemini.bridge.memory.MemoryStore(appContext),
    private val defaultModel: String = DEFAULT_MODEL
) : GeminiCore {

    /** Read-only access to the host application Context for callers wiring
     * Android APIs (SAF, ContentResolver, etc.) that don't belong on the
     * driver itself. */
    val appContext: Context = appContext.applicationContext

    private val registry = ToolRegistry().apply {
        register(ReadFileTool(workspace))
        register(WriteFileTool(workspace))
        register(EditFileTool(workspace))
        register(ListDirTool(workspace))
        register(DeleteFileTool(workspace))
        register(GlobTool(workspace))
        register(GrepTool(workspace))
        register(RunShellCommandTool(termux, workspace))
        register(
            GenerateImageTool(
                getApiKey = { this@RestGeminiCore.apiKey },
                getModel = { prefs.imagenModel ?: DEFAULT_IMAGEN_MODEL },
                persist = { id, bytes, mime ->
                    persistAttachmentBytes(id, bytes, mime)
                }
            )
        )
        register(com.gemini.bridge.tools.RememberFactTool(memory))
        register(com.gemini.bridge.tools.ForgetFactTool(memory))
        register(com.gemini.bridge.tools.RecallMemoryTool(memory))
        register(com.gemini.bridge.tools.NoteWriteTool(memory))
        register(com.gemini.bridge.tools.ListUserFilesTool(this@RestGeminiCore.appContext, prefs))
    }

    private var apiKey: String = ""
    private var accessToken: String = ""
    /** OAuth refresh token — present only in CodeAssistOAuth mode. */
    private var refreshToken: String = ""
    /** Absolute expiry of [accessToken] in epoch ms. 0 = unknown. */
    private var tokenExpiryEpochMs: Long = 0L
    /** Cloud AI Companion project resolved by loadCodeAssist; cached in prefs. */
    private var codeAssistProjectId: String = ""
    /** Per-conversation Code Assist session id. Stable across turns. */
    private val codeAssistSessionId: String = java.util.UUID.randomUUID().toString()
    /** Lazy CodeAssistClient — instantiated when OAuth tokens are present. */
    private val codeAssistClient: com.gemini.bridge.codeassist.CodeAssistClient by lazy {
        com.gemini.bridge.codeassist.CodeAssistClient(
            tokens = {
                com.gemini.bridge.storage.OAuthTokens(
                    accessToken, refreshToken, tokenExpiryEpochMs, codeAssistProjectId.ifBlank { null }
                )
            },
            onRefreshNeeded = { onTokenRefreshRequested?.invoke() },
        )
    }
    /** Hook the app injects to drive PKCE refresh via [GeminiCliAuthService]. */
    @Volatile var onTokenRefreshRequested: (suspend () -> com.gemini.bridge.storage.OAuthTokens?)? = null
    private var model: String = defaultModel
    private var autoApproveDestructive = false
    private var idCounter = 0L

    private enum class AuthMode { ApiKey, CodeAssistOAuth }
    private val authMode: AuthMode
        get() = if (accessToken.isNotBlank()) AuthMode.CodeAssistOAuth else AuthMode.ApiKey

    private val turns = mutableListOf<JSONObject>()
    private val uiMessages = mutableListOf<GeminiMessage>()
    private val sendLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ToolDecision>>()

    @Volatile private var discoveredModels: List<String> = AVAILABLE_MODELS
    private val tokenLimits = ConcurrentHashMap<String, Int>()
    @Volatile private var lastTokenUsage: Int = 0

    /**
     * Caller-supplied system-prompt override. Set via [setSystemPrompt];
     * when non-null it's prepended to the built-in instruction so
     * persona behaviour layers on top of the workspace/tool preamble.
     */
    @Volatile private var customSystemPrompt: String? = null

    init {
        // Restore non-sensitive prefs eagerly; the API key is injected via init().
        prefs.model?.let { if (it.isNotBlank()) model = it }
        autoApproveDestructive = prefs.autoApprove
        prefs.workspaceUri?.let { uri ->
            runCatching { workspace.setTreeUri(Uri.parse(uri)) }
        }
    }

    fun persistedApiKey(): String? = prefs.apiKey
    fun persistedAccessToken(): String? = prefs.accessToken
    fun persistedOAuthTokens(): com.gemini.bridge.storage.OAuthTokens? = prefs.oauthTokens
    fun hasPersistedSession(): Boolean =
        !prefs.apiKey.isNullOrBlank() || prefs.oauthTokens != null || !prefs.accessToken.isNullOrBlank()

    private val _events = MutableSharedFlow<GeminiEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<GeminiEvent> = _events.asSharedFlow()

    override fun availableTools(): List<ToolSpec> = registry.specs()

    override suspend fun init(config: Map<String, Any>): GeminiResult {
        val rememberKey = (config["remember"] as? Boolean) ?: true
        apiKey = (config["api_key"] ?: config["apiKey"] ?: "").toString().trim()
        accessToken = (config["access_token"] ?: config["accessToken"] ?: "").toString().trim()
        refreshToken = (config["refresh_token"] ?: "").toString().trim()
        tokenExpiryEpochMs = (config["token_expiry"] as? Number)?.toLong() ?: 0L
        codeAssistProjectId = (config["project_id"] ?: "").toString().trim()
        val requestedModel = (config["model"] as? String)?.ifBlank { null }
        if (requestedModel != null) model = requestedModel
        if (apiKey.isBlank() && accessToken.isBlank()) return GeminiResult.Error("API key or access token is required")

        if (authMode == AuthMode.CodeAssistOAuth) {
            // Gemini-cli boot sequence: loadCodeAssist → onboardUser (if needed)
            // → cache projectId. Skip onboarding if we already have a project
            // cached from a previous session.
            val cachedProject = prefs.oauthTokens?.projectId
            if (!cachedProject.isNullOrBlank()) {
                codeAssistProjectId = cachedProject
            } else {
                runCatching {
                    val info = codeAssistClient.loadCodeAssist()
                    val project = info.cloudaicompanionProject
                    codeAssistProjectId = if (info.isOnboarded && !project.isNullOrBlank()) {
                        project
                    } else {
                        val tier = info.currentTier
                            ?: info.allowedTiers.firstOrNull { it.contains("free", ignoreCase = true) }
                            ?: "free-tier"
                        codeAssistClient.onboardUser(tier, project).projectId.orEmpty()
                    }
                }.onFailure { Log.w(TAG, "Code Assist boot failed: ${it.message}") }
            }
            // For OAuth, gemini-cli is hard-wired to gemini-2.5-pro / -flash —
            // skip the public-API model discovery which doesn't work against
            // cloudcode-pa.
            if (discoveredModels === AVAILABLE_MODELS || discoveredModels.isEmpty()) {
                discoveredModels = listOf("gemini-2.5-pro", "gemini-2.5-flash")
            }
            if (model !in discoveredModels) model = "gemini-2.5-flash"
        } else {
            // API-key mode: discover models from the public API.
            runCatching { discoveredModels = fetchAvailableModels() }
                .onFailure { Log.w(TAG, "model discovery failed: ${it.message}") }
            if (model !in discoveredModels && discoveredModels.isNotEmpty()) {
                model = discoveredModels.firstOrNull { it.contains("flash") } ?: discoveredModels.first()
            }
        }

        if (rememberKey) {
            prefs.apiKey = apiKey
            if (authMode == AuthMode.CodeAssistOAuth) {
                prefs.oauthTokens = com.gemini.bridge.storage.OAuthTokens(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiryEpochMs = tokenExpiryEpochMs,
                    projectId = codeAssistProjectId.ifBlank { null },
                )
            } else {
                prefs.accessToken = accessToken
            }
        }
        prefs.model = model
        return GeminiResult.Success("Ready")
    }

    fun setModel(newModel: String) {
        model = newModel.ifBlank { defaultModel }
        prefs.model = model
    }
    fun currentModel(): String = model
    fun listModels(): List<String> = discoveredModels
    suspend fun refreshModels(): List<String> = runCatching { fetchAvailableModels() }
        .onSuccess { discoveredModels = it }
        .getOrDefault(discoveredModels)
    fun setAutoApprove(enabled: Boolean) {
        autoApproveDestructive = enabled
        prefs.autoApprove = enabled
    }
    fun isAutoApprove(): Boolean = autoApproveDestructive

    fun isTermuxGuideShown(): Boolean = prefs.termuxGuideShown
    fun markTermuxGuideShown() { prefs.termuxGuideShown = true }

    fun isAutoCompressEnabled(): Boolean = prefs.autoCompressEnabled
    fun setAutoCompressEnabled(enabled: Boolean) { prefs.autoCompressEnabled = enabled }
    fun autoCompressThreshold(): Float = prefs.autoCompressThreshold

    fun imagenModel(): String = prefs.imagenModel ?: DEFAULT_IMAGEN_MODEL
    fun setImagenModel(name: String) {
        prefs.imagenModel = name.ifBlank { DEFAULT_IMAGEN_MODEL }
    }

    /**
     * Save raw image bytes to app-owned storage for later display in a chat
     * bubble. Called by the response parser (for model-generated images) and
     * by the GenerateImageTool (for Imagen outputs). Returns the absolute path
     * that can be stored in `GeminiMessage.attachmentPaths`.
     */
    fun persistAttachmentBytes(id: String, bytes: ByteArray, mime: String): String? = runCatching {
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            mime.contains("heic") -> "heic"
            mime.contains("heif") -> "heif"
            else -> "jpg"
        }
        val dir = java.io.File(appContext.filesDir, "attachments").also { it.mkdirs() }
        val file = java.io.File(dir, "$id.$ext")
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrNull()
    fun setAutoCompressThreshold(fraction: Float) {
        prefs.autoCompressThreshold = fraction.coerceIn(0.5f, 0.95f)
    }

    fun isAutoSaveEnabled(): Boolean = prefs.autoSaveEnabled
    fun setAutoSaveEnabled(enabled: Boolean) {
        prefs.autoSaveEnabled = enabled
        if (!enabled) chatStore.clearCurrent()
        else persistCurrentIfEnabled()
    }

    /** Snapshot the live session into the auto-save slot (no-op if disabled). */
    fun persistCurrentIfEnabled() {
        if (!prefs.autoSaveEnabled) return
        if (turns.isEmpty() && uiMessages.isEmpty()) {
            chatStore.clearCurrent(); return
        }
        chatStore.saveCurrent(ChatStore.Snapshot(turns.toList(), uiMessages.toList()))
    }

    /** Restore the auto-save slot into the live session, if one exists. */
    suspend fun resumeCurrentSession(): Boolean {
        val snap = chatStore.loadCurrent() ?: return false
        turns.clear()
        turns.addAll(snap.turns)
        uiMessages.clear()
        uiMessages.addAll(snap.messages)
        pending.values.forEach { it.cancel() }
        pending.clear()
        snap.messages.forEach { _events.tryEmit(GeminiEvent.MessageAdded(it)) }
        return true
    }

    /** Last reported (totalTokenCount, inputTokenLimit?) for the current model. */
    fun currentTokenUsage(): Pair<Int, Int?> = lastTokenUsage to tokenLimits[model]

    fun signOut() {
        apiKey = ""
        accessToken = ""
        refreshToken = ""
        tokenExpiryEpochMs = 0L
        codeAssistProjectId = ""
        turns.clear()
        uiMessages.clear()
        pending.values.forEach { it.cancel() }
        pending.clear()
        discoveredModels = AVAILABLE_MODELS
        model = defaultModel
        chatStore.clearCurrent()
        prefs.clearAll()
    }

    // --- chat persistence ---
    fun listChats(): List<ChatStore.Entry> = chatStore.list()
    fun saveChat(name: String) {
        chatStore.save(name, ChatStore.Snapshot(turns.toList(), uiMessages.toList()))
    }
    fun deleteChat(name: String): Boolean = chatStore.delete(name)
    suspend fun resumeChat(name: String): Boolean {
        val snap = chatStore.load(name) ?: return false
        turns.clear()
        turns.addAll(snap.turns)
        uiMessages.clear()
        uiMessages.addAll(snap.messages)
        pending.values.forEach { it.cancel() }
        pending.clear()
        // Replay messages to the UI.
        snap.messages.forEach { _events.tryEmit(GeminiEvent.MessageAdded(it)) }
        persistCurrentIfEnabled()
        return true
    }

    private suspend fun fetchAvailableModels(): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() && accessToken.isBlank()) return@withContext AVAILABLE_MODELS
        val url = "https://generativelanguage.googleapis.com/v1beta/models" +
            (if (apiKey.isNotBlank()) "?key=${URLEncoder.encode(apiKey, "UTF-8")}&pageSize=200" else "?pageSize=200")
        val (code, body) = getJson(url)
        if (code !in 200..299) return@withContext AVAILABLE_MODELS
        val arr = JSONObject(body).optJSONArray("models") ?: return@withContext AVAILABLE_MODELS
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")
            val supportsGen = methods != null && (0 until methods.length())
                .any { methods.optString(it) == "generateContent" }
            if (!supportsGen) continue
            val raw = m.optString("name") // "models/gemini-2.5-flash"
            val name = raw.removePrefix("models/")
            // Skip embeddings / vision-only / non-chat suffixes.
            if (name.contains("embedding") || name.contains("aqa")) continue
            val limit = m.optInt("inputTokenLimit", 0)
            if (limit > 0) tokenLimits[name] = limit
            out += name
        }
        // Prefer Gemini chat models, sort newest-looking first.
        out.distinct().sortedWith(compareByDescending<String> {
            when {
                it.startsWith("gemini-3") -> 4
                it.startsWith("gemini-2.5") -> 3
                it.startsWith("gemini-2.0") -> 2
                it.startsWith("gemini-1.5") -> 1
                else -> 0
            }
        }.thenBy { it })
    }

    override suspend fun setProjectFolder(uri: String): GeminiResult = runCatching {
        workspace.setTreeUri(Uri.parse(uri))
        prefs.workspaceUri = uri
        GeminiResult.Success("Project folder set to ${workspace.rootLabel()}")
    }.getOrElse { GeminiResult.Error(it.message ?: "Invalid folder URI") }

    override suspend fun resolveToolDecision(callId: String, decision: ToolDecision) {
        pending.remove(callId)?.complete(decision)
    }

    override suspend fun sendMessage(text: String): GeminiResult = sendMessage(text, emptyList())

    override suspend fun sendMessage(text: String, attachments: List<Attachment>): GeminiResult = sendLock.withLock {
        if (apiKey.isBlank() && accessToken.isBlank()) return GeminiResult.Error("API key or access token not configured")

        val preTurnSize = turns.size
        appendUserTurn(text, attachments)
        val bubbleText = buildString {
            if (attachments.isNotEmpty()) {
                append("📎 ")
                append(attachments.joinToString(", ") { describeAttachment(it) })
                if (text.isNotBlank()) append('\n')
            }
            append(text)
        }
        addUiMessage(
            GeminiMessage(
                id = nextId(),
                text = bubbleText,
                isUser = true,
                timestamp = System.currentTimeMillis(),
                role = MessageRole.USER,
                attachmentPaths = attachments.mapNotNull { it.localPath }
            )
        )

        var lastText = ""
        try {
            while (true) {
                emitThinking("Thinking…")
                val (textPart, calls) = streamOnce()
                if (!textPart.isNullOrBlank()) lastText = textPart
                if (calls.isEmpty()) {
                    emitThinking(null)
                    return GeminiResult.Success(lastText)
                }

                val responseParts = JSONArray()
                for (call in calls) {
                    emitThinking("Running ${call.name}…")
                    val result = runSingleCall(call)
                    responseParts.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject()
                                .put("name", call.name)
                                .put(
                                    "response",
                                    JSONObject()
                                        .put("ok", result.ok)
                                        .put("output", result.output)
                                )
                        )
                    )
                }
                turns.add(JSONObject().put("role", "user").put("parts", responseParts))
            }
            @Suppress("UNREACHABLE_CODE")
            GeminiResult.Success(lastText)
        } catch (t: Throwable) {
            emitThinking(null)
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.e(TAG, "sendMessage failed", t)
            while (turns.size > preTurnSize) turns.removeAt(turns.size - 1)
            GeminiResult.Error(t.message ?: "Network error")
        } finally {
            emitThinking(null)
            persistCurrentIfEnabled()
        }
    }

    private fun emitThinking(label: String?) {
        _events.tryEmit(GeminiEvent.Thinking(label))
    }

    override suspend fun resetSession(): GeminiResult {
        turns.clear()
        uiMessages.clear()
        pending.values.forEach { it.cancel() }
        pending.clear()
        lastTokenUsage = 0
        _events.tryEmit(GeminiEvent.TokenUsage(0, tokenLimits[model]))
        chatStore.clearCurrent()
        return GeminiResult.Success("Reset")
    }

    override suspend fun loadHistory(): List<GeminiMessage> = uiMessages.toList()

    override suspend fun setSystemPrompt(prompt: String?) {
        customSystemPrompt = prompt?.takeIf { it.isNotBlank() }
    }

    // --- tool call flow ---

    private suspend fun runSingleCall(call: ToolCall): ToolCallResult {
        val tool = registry.get(call.name)
            ?: return completeCall(call, ToolCallResult(call.id, false, "Unknown tool: ${call.name}"))

        addUiMessage(
            GeminiMessage(
                id = nextId(),
                text = "Calling ${call.name}(${summariseArgs(call.arguments)})",
                isUser = false,
                timestamp = System.currentTimeMillis(),
                role = MessageRole.TOOL,
                toolCall = call
            )
        )
        _events.tryEmit(GeminiEvent.ToolCallPending(call))

        val decision = decide(tool, call)
        val result = when (decision) {
            ToolDecision.Approve -> tool.execute(call)
            ToolDecision.AlwaysApprove -> {
                autoApproveDestructive = true
                tool.execute(call)
            }
            is ToolDecision.Reject -> ToolCallResult(
                call.id, false, "Rejected by user: ${decision.reason}"
            )
        }
        return completeCall(call, result)
    }

    private suspend fun decide(tool: Tool, call: ToolCall): ToolDecision {
        if (!tool.spec.destructive || autoApproveDestructive) return ToolDecision.Approve
        val deferred = CompletableDeferred<ToolDecision>()
        pending[call.id] = deferred
        return try { deferred.await() } catch (_: Throwable) { ToolDecision.Reject("cancelled") }
    }

    private fun completeCall(call: ToolCall, result: ToolCallResult): ToolCallResult {
        addUiMessage(
            GeminiMessage(
                id = nextId(),
                text = if (result.ok) result.output else "✗ ${result.output}",
                isUser = false,
                timestamp = System.currentTimeMillis(),
                role = MessageRole.TOOL,
                toolResult = result,
                attachmentPaths = result.attachmentPaths
            )
        )
        _events.tryEmit(GeminiEvent.ToolCallCompleted(result))
        return result
    }

    // --- HTTP + parsing ---

    /**
     * Mutable state accumulated across one streaming response. Lives only for
     * the duration of [streamOnce] so it doesn't need synchronization beyond
     * the single coroutine that owns it.
     */
    private class StreamState {
        val accumulated = StringBuilder()
        val parts = JSONArray()
        val calls = mutableListOf<ToolCall>()
        var liveMessage: GeminiMessage? = null
        var lastTotalTokens: Int = 0
    }

    private suspend fun streamOnce(): Pair<String?, List<ToolCall>> = withContext(Dispatchers.IO) {
        val state = StreamState()
        when (authMode) {
            AuthMode.CodeAssistOAuth -> streamOnceCodeAssist(state)
            AuthMode.ApiKey -> streamOnceApiKey(state)
        }
        if (state.parts.length() > 0) {
            turns.add(JSONObject().put("role", "model").put("parts", state.parts))
        }
        if (state.lastTotalTokens > 0) {
            lastTokenUsage = state.lastTotalTokens
            _events.tryEmit(GeminiEvent.TokenUsage(state.lastTotalTokens, tokenLimits[model]))
        }
        val textPart = state.accumulated.toString().takeIf { it.isNotBlank() }
        textPart to state.calls
    }

    /**
     * Code Assist (OAuth) path — sends through cloudcode-pa.googleapis.com via
     * [com.gemini.bridge.codeassist.CodeAssistClient]. Response chunks have
     * the same shape as the public Gemini API, so they feed [processChunkJson]
     * unchanged.
     */
    private suspend fun streamOnceCodeAssist(state: StreamState) {
        if (codeAssistProjectId.isBlank()) {
            throw RuntimeException("Code Assist project id is unknown — re-login required")
        }
        val request = JSONObject(buildRequestBody())
        codeAssistClient.streamGenerateContent(
            model = model,
            sessionId = codeAssistSessionId,
            projectId = codeAssistProjectId,
            request = request,
        ) { chunk ->
            processChunkJson(chunk.json, state)
        }
    }

    /**
     * Public Gemini API (API-key) path — direct HTTP to
     * `generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent`.
     */
    private suspend fun streamOnceApiKey(state: StreamState) = withContext(Dispatchers.IO) {
        val body = buildRequestBody()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$model:streamGenerateContent?alt=sse" +
            "&key=${URLEncoder.encode(apiKey, "UTF-8")}"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream)
                    ?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
                    .orEmpty()
                throw RuntimeException(tryExtractError(err) ?: "HTTP $code")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    processChunkJson(json, state)
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** Shared per-chunk parser. Reads from [json] and updates [state] in-place. */
    private fun processChunkJson(json: JSONObject, state: StreamState) {
        json.optJSONObject("usageMetadata")
            ?.optInt("totalTokenCount", 0)
            ?.takeIf { it > 0 }
            ?.let { state.lastTotalTokens = it }
        val content = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?: return
        val chunkParts = content.optJSONArray("parts") ?: return
        consumeChunkParts(chunkParts, state)
    }

    private fun consumeChunkParts(chunkParts: JSONArray, state: StreamState) {
        val parts = state.parts
        val accumulated = state.accumulated
        val calls = state.calls
        for (i in 0 until chunkParts.length()) {
            val part = chunkParts.getJSONObject(i)
            parts.put(part)
            when {
                part.has("text") -> {
                    val t = part.optString("text")
                    if (t.isEmpty()) continue
                    accumulated.append(t)
                    val current = state.liveMessage
                    if (current == null) {
                        val msg = GeminiMessage(
                            id = nextId(),
                            text = accumulated.toString(),
                            isUser = false,
                            timestamp = System.currentTimeMillis(),
                            role = MessageRole.MODEL
                        )
                        state.liveMessage = msg
                        addUiMessage(msg)
                    } else {
                        val msg = current.copy(text = accumulated.toString())
                        state.liveMessage = msg
                        replaceUiMessage(msg)
                    }
                }
                part.has("functionCall") -> {
                    val fc = part.getJSONObject("functionCall")
                    val name = fc.optString("name")
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    val id = "$name-${System.nanoTime()}-${calls.size}"
                    calls.add(ToolCall(id, name, jsonToMap(args)))
                }
                part.has("inlineData") -> {
                    // Native multimodal output (e.g.
                    // `gemini-2.5-flash-image-preview`): the model returns
                    // image bytes alongside text parts. Persist to disk so the
                    // bubble can show a thumbnail and the file survives reload.
                    val inline = part.getJSONObject("inlineData")
                    val mime = inline.optString("mimeType", "image/png")
                    val b64 = inline.optString("data").orEmpty()
                    if (b64.isNotBlank()) {
                        val decoded = runCatching {
                            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                        }.getOrNull()
                        val path = decoded?.let { bytes ->
                            persistAttachmentBytes("gen-${nextId()}", bytes, mime)
                        }
                        if (path != null) {
                            val current = state.liveMessage
                            if (current == null) {
                                val msg = GeminiMessage(
                                    id = nextId(),
                                    text = accumulated.toString(),
                                    isUser = false,
                                    timestamp = System.currentTimeMillis(),
                                    role = MessageRole.MODEL,
                                    attachmentPaths = listOf(path)
                                )
                                state.liveMessage = msg
                                addUiMessage(msg)
                            } else {
                                val msg = current.copy(
                                    attachmentPaths = current.attachmentPaths + path
                                )
                                state.liveMessage = msg
                                replaceUiMessage(msg)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun buildRequestBody(): String {
        val contents = JSONArray()
        turns.forEach { contents.put(it) }
        val body = JSONObject().put("contents", contents)
        val emitsImages = modelEmitsImages(model)
        val supportsTools = modelSupportsFunctionCalling(model)
        // Gemma + image-only models reject systemInstruction on the generateContent
        // endpoint ("400: system_instruction is not supported"). Gemini 2.5+ is
        // fine and benefits from the workspace/tool preamble.
        if (supportsTools) {
            body.put("systemInstruction", buildSystemInstruction())
            body.put("tools", buildToolsJson())
        }
        if (emitsImages) {
            body.put(
                "generationConfig",
                JSONObject().put(
                    "responseModalities",
                    JSONArray().put("TEXT").put("IMAGE")
                )
            )
        }
        return body.toString()
    }

    private fun buildSystemInstruction(): JSONObject {
        val toolNames = registry.specs().joinToString(", ") { it.name }
        val reachable = workspace.absolutePath()
        val reason = workspace.unreachableReason()
        val termuxLine = when {
            !termux.isInstalled() ->
                "Termux is not installed, so run_shell_command will fail. " +
                    "Use the file tools for anything file-related."
            reachable != null ->
                "The run_shell_command tool dispatches to Termux. It changes " +
                    "directory to the workspace (`$reachable`) before running, so " +
                    "`python foo.py` resolves to files the write_file tool just " +
                    "created. Termux must have been granted storage access " +
                    "(`termux-setup-storage`) for this to work. Requires Termux from " +
                    "F-Droid or GitHub (not the Play Store build) with the " +
                    "RUN_COMMAND permission."
            else ->
                "The run_shell_command tool dispatches to Termux, but the current " +
                    "workspace is NOT reachable from Termux's filesystem view. " +
                    "Exact reason: ${reason ?: "unknown"}. Shell commands run in " +
                    "Termux's `\$HOME` with no access to workspace files. For " +
                    "anything file-oriented, use the file tools (read_file, " +
                    "write_file, edit_file, list_directory, glob_files, grep) " +
                    "instead of `cat`, `ls`, `grep`, or invoking interpreters on " +
                    "workspace files. When the user asks why a shell command " +
                    "failed with \"No such file or directory\", relay the exact " +
                    "reason above — the fix is almost always picking a folder " +
                    "under /storage/emulated/0/ via Settings → Workspace."
        }
        val text = buildString {
            customSystemPrompt?.let { persona ->
                append(persona).append("\n\n---\n\n")
            }
            append("You are Gemini running inside a native Android app.\n\n")
            append("Workspace: ").append(workspace.rootLabel()).append('\n')
            if (reachable != null) {
                append("Workspace device path (Termux-visible): ").append(reachable).append('\n')
            } else {
                append("Workspace device path: not reachable from Termux.\n")
            }
            append('\n')
            append(
                "You have tools that read, write, search, and run commands on this " +
                    "device. Available tools: "
            ).append(toolNames).append(".\n\n")
            append(
                "Path rules: always use paths RELATIVE to the workspace root when " +
                    "calling file tools. Never invent absolute paths or paths with " +
                    "`..`. Use list_directory or glob_files to discover what's there " +
                    "before reading/writing.\n\n"
            )
            append(
                "When the user asks you to read, create, edit, or delete a file, " +
                    "call the appropriate tool right away — don't ask for confirmation " +
                    "in chat. Destructive tools (write_file, edit_file, delete_file, " +
                    "run_shell_command) prompt the user in the UI; that's enough.\n\n"
            )
            append(termuxLine)
            val facts = memory.factsForSystemPrompt()
            if (facts.isNotBlank()) {
                append("\n\n").append(facts)
            }
        }
        return JSONObject().put(
            "parts",
            JSONArray().put(JSONObject().put("text", text))
        )
    }

    private fun buildToolsJson(): JSONArray {
        val decls = JSONArray()
        registry.specs().forEach { spec ->
            decls.put(
                JSONObject()
                    .put("name", spec.name)
                    .put("description", spec.description)
                    .put("parameters", mapToJson(spec.parameters))
            )
        }
        return JSONArray().put(JSONObject().put("functionDeclarations", decls))
    }

    private fun describeAttachment(att: Attachment): String {
        val shortMime = att.mimeType.substringAfter('/').take(8)
        val kb = (att.bytes.size + 1023) / 1024
        return "image ($shortMime, ${kb}KB)"
    }

    private fun appendUserTurn(text: String, attachments: List<Attachment> = emptyList()) {
        val parts = JSONArray()
        attachments.forEach { att ->
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", att.mimeType)
                        .put("data", Base64.encodeToString(att.bytes, Base64.NO_WRAP))
                )
            )
        }
        if (text.isNotEmpty() || attachments.isEmpty()) {
            parts.put(JSONObject().put("text", text))
        }
        turns.add(JSONObject().put("role", "user").put("parts", parts))
    }

    private fun addUiMessage(msg: GeminiMessage): GeminiMessage {
        uiMessages.add(msg)
        _events.tryEmit(GeminiEvent.MessageAdded(msg))
        return msg
    }

    private fun replaceUiMessage(msg: GeminiMessage) {
        val idx = uiMessages.indexOfLast { it.id == msg.id }
        if (idx >= 0) uiMessages[idx] = msg
        _events.tryEmit(GeminiEvent.MessageUpdated(msg))
    }

    private fun getJson(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")
        if (accessToken.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
        }
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val text = stream.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }
            code to text
        } finally {
            conn.disconnect()
        }
    }

    private fun tryExtractError(body: String): String? = runCatching {
        val raw = JSONObject(body).optJSONObject("error")?.optString("message") ?: return@runCatching null
        if (looksLikeImageFreeTierQuota(raw)) {
            raw + "\n\n" +
                "Image generation models (Imagen, Nano Banana / *-image) are " +
                "not included in the Gemini free tier (limit 0). Enable " +
                "billing on the Google Cloud project linked to your API key: " +
                "https://console.cloud.google.com/billing — then retry."
        } else raw
    }.getOrNull()

    private fun looksLikeImageFreeTierQuota(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("quota") &&
            m.contains("limit: 0") &&
            (m.contains("-image") || m.contains("imagen"))
    }

    private fun mapToJson(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, toJson(v)) }
        return obj
    }

    @Suppress("UNCHECKED_CAST")
    private fun toJson(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> mapToJson(value as Map<String, Any?>)
            is List<*> -> JSONArray().also { arr -> value.forEach { arr.put(toJson(it)) } }
            else -> value
        }
    }

    private fun jsonToMap(obj: JSONObject): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            result[key] = when (val v = obj.get(key)) {
                is JSONObject -> jsonToMap(v)
                is JSONArray -> (0 until v.length()).map { v.get(it) }
                JSONObject.NULL -> null
                else -> v
            }
        }
        return result
    }

    private fun summariseArgs(args: Map<String, Any?>): String =
        args.entries.joinToString(", ") { (k, v) ->
            val str = v?.toString().orEmpty()
            val trimmed = if (str.length > 40) str.substring(0, 40) + "…" else str
            "$k=$trimmed"
        }

    private fun nextId(): String { idCounter++; return "$idCounter-${System.nanoTime()}" }

    companion object {
        private const val TAG = "RestGeminiCore"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val DEFAULT_IMAGEN_MODEL = "imagen-3.0-generate-002"
        // Used as a static fallback before the API model-discovery call lands.
        // The real model list is fetched live from /v1beta/models.
        val AVAILABLE_MODELS = listOf(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-2.5-flash-image-preview",
            "gemini-2.0-flash",
            "gemini-1.5-pro-latest",
            "gemini-1.5-flash-latest"
        )
        // Imagen variants we expose in the settings picker. Access depends on
        // the API key's quota — Imagen is billed separately from Gemini.
        val AVAILABLE_IMAGEN_MODELS = listOf(
            "imagen-3.0-generate-002",
            "imagen-3.0-fast-generate-001",
            "imagen-4.0-generate-preview-06-06"
        )

        /**
         * True when the selected chat model is known to return inline image
         * data in its response (triggers `responseModalities = [TEXT, IMAGE]`).
         * Kept as a name-based heuristic so newly released image-output models
         * light up automatically once the user picks them.
         */
        fun modelEmitsImages(modelName: String): Boolean =
            modelName.contains("-image", ignoreCase = true)

        /**
         * True when the model accepts `tools` + `systemInstruction` in the
         * request body. Gemma models and image-only models do not (the API
         * returns 400). Gemini text/multimodal models do.
         */
        fun modelSupportsFunctionCalling(modelName: String): Boolean {
            val lower = modelName.lowercase()
            if (lower.startsWith("gemma")) return false
            if (lower.startsWith("imagen")) return false
            // `*-image*` chat models (Nano Banana etc.) reject tools today —
            // they only produce images + short captions.
            if (lower.contains("-image")) return false
            return true
        }
    }
}
