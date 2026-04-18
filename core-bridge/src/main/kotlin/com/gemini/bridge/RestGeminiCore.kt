package com.gemini.bridge

import android.content.Context
import android.net.Uri
import android.util.Log
import com.gemini.bridge.termux.TermuxBridge
import com.gemini.bridge.tools.DeleteFileTool
import com.gemini.bridge.tools.EditFileTool
import com.gemini.bridge.tools.GlobTool
import com.gemini.bridge.tools.GrepTool
import com.gemini.bridge.tools.ListDirTool
import com.gemini.bridge.tools.ReadFileTool
import com.gemini.bridge.tools.RunShellCommandTool
import com.gemini.bridge.tools.Tool
import com.gemini.bridge.tools.ToolRegistry
import com.gemini.bridge.tools.WriteFileTool
import com.gemini.bridge.workspace.Workspace
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val defaultModel: String = DEFAULT_MODEL
) : GeminiCore {

    private val registry = ToolRegistry().apply {
        register(ReadFileTool(workspace))
        register(WriteFileTool(workspace))
        register(EditFileTool(workspace))
        register(ListDirTool(workspace))
        register(DeleteFileTool(workspace))
        register(GlobTool(workspace))
        register(GrepTool(workspace))
        register(RunShellCommandTool(termux))
    }

    private var apiKey: String = ""
    private var model: String = defaultModel
    private var autoApproveDestructive = false
    private var idCounter = 0L

    private val turns = mutableListOf<JSONObject>()
    private val uiMessages = mutableListOf<GeminiMessage>()
    private val sendLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ToolDecision>>()

    private val _events = MutableSharedFlow<GeminiEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: SharedFlow<GeminiEvent> = _events.asSharedFlow()

    override fun availableTools(): List<ToolSpec> = registry.specs()

    override suspend fun init(config: Map<String, Any>): GeminiResult {
        apiKey = (config["api_key"] ?: config["apiKey"] ?: "").toString().trim()
        model = (config["model"] as? String)?.ifBlank { defaultModel } ?: defaultModel
        return if (apiKey.isBlank()) GeminiResult.Error("API key is required")
        else GeminiResult.Success("Ready")
    }

    fun setModel(newModel: String) { model = newModel.ifBlank { defaultModel } }
    fun currentModel(): String = model
    fun setAutoApprove(enabled: Boolean) { autoApproveDestructive = enabled }
    fun isAutoApprove(): Boolean = autoApproveDestructive

    override suspend fun setProjectFolder(uri: String): GeminiResult = runCatching {
        workspace.setTreeUri(Uri.parse(uri))
        GeminiResult.Success("Project folder set to ${workspace.rootLabel()}")
    }.getOrElse { GeminiResult.Error(it.message ?: "Invalid folder URI") }

    override suspend fun resolveToolDecision(callId: String, decision: ToolDecision) {
        pending.remove(callId)?.complete(decision)
    }

    override suspend fun sendMessage(text: String): GeminiResult = sendLock.withLock {
        if (apiKey.isBlank()) return GeminiResult.Error("API key not configured")

        val preTurnSize = turns.size
        appendUserTurn(text)
        addUiMessage(
            GeminiMessage(
                id = nextId(),
                text = text,
                isUser = true,
                timestamp = System.currentTimeMillis(),
                role = MessageRole.USER
            )
        )

        var lastText = ""
        try {
            while (true) {
                val (textPart, calls) = callOnce()
                if (!textPart.isNullOrBlank()) {
                    lastText = textPart
                    addUiMessage(
                        GeminiMessage(
                            id = nextId(),
                            text = textPart,
                            isUser = false,
                            timestamp = System.currentTimeMillis(),
                            role = MessageRole.MODEL
                        )
                    )
                }
                if (calls.isEmpty()) return GeminiResult.Success(lastText)

                val responseParts = JSONArray()
                for (call in calls) {
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
            Log.e(TAG, "sendMessage failed", t)
            while (turns.size > preTurnSize) turns.removeAt(turns.size - 1)
            GeminiResult.Error(t.message ?: "Network error")
        }
    }

    override suspend fun resetSession(): GeminiResult {
        turns.clear()
        uiMessages.clear()
        pending.values.forEach { it.cancel() }
        pending.clear()
        return GeminiResult.Success("Reset")
    }

    override suspend fun loadHistory(): List<GeminiMessage> = uiMessages.toList()

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
                toolResult = result
            )
        )
        _events.tryEmit(GeminiEvent.ToolCallCompleted(result))
        return result
    }

    // --- HTTP + parsing ---

    private fun callOnce(): Pair<String?, List<ToolCall>> {
        val body = buildRequestBody()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
            "$model:generateContent?key=${URLEncoder.encode(apiKey, "UTF-8")}"
        val (code, rawBody) = postJson(url, body)
        if (code !in 200..299) {
            val msg = tryExtractError(rawBody) ?: "HTTP $code"
            throw RuntimeException(msg)
        }
        return parseResponse(rawBody)
    }

    private fun buildRequestBody(): String {
        val contents = JSONArray()
        turns.forEach { contents.put(it) }
        return JSONObject()
            .put("contents", contents)
            .put("tools", buildToolsJson())
            .toString()
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

    private fun parseResponse(body: String): Pair<String?, List<ToolCall>> {
        val json = JSONObject(body)
        val candidates = json.optJSONArray("candidates") ?: return null to emptyList()
        if (candidates.length() == 0) return null to emptyList()
        val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null to emptyList()
        val parts = content.optJSONArray("parts") ?: return null to emptyList()

        val textBuf = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            when {
                part.has("text") -> textBuf.append(part.optString("text"))
                part.has("functionCall") -> {
                    val fc = part.getJSONObject("functionCall")
                    val name = fc.optString("name")
                    val args = fc.optJSONObject("args") ?: JSONObject()
                    val id = "$name-${System.nanoTime()}-$i"
                    calls.add(ToolCall(id, name, jsonToMap(args)))
                }
            }
        }

        // Persist the model turn verbatim so follow-up requests have context.
        turns.add(JSONObject().put("role", "model").put("parts", parts))
        return textBuf.toString().takeIf { it.isNotBlank() } to calls
    }

    private fun appendUserTurn(text: String) {
        turns.add(
            JSONObject().put("role", "user").put(
                "parts", JSONArray().put(JSONObject().put("text", text))
            )
        )
    }

    private fun addUiMessage(msg: GeminiMessage): GeminiMessage {
        uiMessages.add(msg)
        _events.tryEmit(GeminiEvent.MessageAdded(msg))
        return msg
    }

    private fun postJson(url: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
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
        JSONObject(body).optJSONObject("error")?.optString("message")
    }.getOrNull()

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
        const val DEFAULT_MODEL = "gemini-1.5-flash-latest"
        val AVAILABLE_MODELS = listOf(
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-1.5-pro-latest",
            "gemini-1.5-flash-latest"
        )
    }
}
