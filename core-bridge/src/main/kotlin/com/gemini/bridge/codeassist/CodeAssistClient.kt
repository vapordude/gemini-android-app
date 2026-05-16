package com.gemini.bridge.codeassist

import com.gemini.bridge.storage.OAuthTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Client for Google's internal Code Assist API — the same surface that the
 * official gemini-cli talks to. Endpoints and request shapes are extracted
 * from `packages/core/src/code_assist/server.ts` in
 * https://github.com/google-gemini/gemini-cli.
 *
 * Boot sequence on first OAuth login:
 *   1. loadCodeAssist() — discovers onboarding state + available tiers
 *   2. onboardUser(tier) if not already onboarded — polls until DONE
 *   3. Cache projectId in OAuthTokens.projectId
 *   4. streamGenerateContent() per chat turn
 *
 * All requests carry `Authorization: Bearer ${tokens().accessToken}` and
 * retry once on HTTP 401 after a refresh callback.
 */
class CodeAssistClient(
    private val tokens: () -> OAuthTokens,
    private val onRefreshNeeded: suspend () -> OAuthTokens?,
) {

    /**
     * Discover whether the signed-in user is onboarded, and what tiers are
     * available. The response includes the Cloud AI Companion project id we
     * pass back in every subsequent request.
     */
    suspend fun loadCodeAssist(): LoadCodeAssistResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("metadata", clientMetadata())
        }
        val resp = postJson("loadCodeAssist", body)
        LoadCodeAssistResponse(
            currentTier = resp.optJSONObject("currentTier")?.optString("id"),
            allowedTiers = resp.optJSONArray("allowedTiers")
                ?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("id") } }
                ?: emptyList(),
            cloudaicompanionProject = resp.optString("cloudaicompanionProject").takeIf { it.isNotBlank() },
        )
    }

    /**
     * Onboard the user onto a tier (typically "free-tier" for personal Google
     * accounts). The endpoint is async — it returns a long-running operation
     * id that we poll until `done=true`.
     */
    suspend fun onboardUser(tier: String, project: String?): OnboardUserResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("tierId", tier)
            if (!project.isNullOrBlank()) put("cloudaicompanionProject", project)
            put("metadata", clientMetadata())
        }
        // Initial call returns an operation; poll until done.
        var op = postJson("onboardUser", body)
        var attempts = 0
        while (!op.optBoolean("done", false) && attempts < 60) {
            currentCoroutineContext().ensureActive()
            Thread.sleep(2000L)
            op = postJson("onboardUser", body)
            attempts++
        }
        val response = op.optJSONObject("response")
        OnboardUserResponse(
            done = op.optBoolean("done", false),
            projectId = response?.optJSONObject("cloudaicompanionProject")?.optString("id")
                ?: response?.optString("cloudaicompanionProject")?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * SSE streaming generate. The body wraps a standard `GenerateContentRequest`
     * with the Code Assist envelope: `project`, `request`, `userPromptId`.
     * We emit one [GenerateContentChunk] per `data:` line.
     */
    suspend fun streamGenerateContent(
        model: String,
        sessionId: String,
        projectId: String,
        request: JSONObject,
        onChunk: suspend (GenerateContentChunk) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("project", projectId)
            put("user_prompt_id", UUID.randomUUID().toString())
            put("session_id", sessionId)
            put("request", request)
        }
        val url = "$BASE_URL/$API_VERSION:streamGenerateContent?alt=sse"
        streamSse(url, body, onChunk)
    }

    suspend fun countTokens(model: String, request: JSONObject): Int = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("request", request)
        }
        val resp = postJson("countTokens", body)
        resp.optInt("totalTokens", 0)
    }

    /** POST `{BASE_URL}/{API_VERSION}:{method}` with a JSON body and Bearer token. */
    private suspend fun postJson(method: String, body: JSONObject): JSONObject {
        val url = "$BASE_URL/$API_VERSION:$method"
        return execWithRetry { access ->
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $access")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 20_000
                readTimeout = 120_000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            HttpResult(code, text)
        }.let { JSONObject(it) }
    }

    private suspend fun streamSse(
        url: String,
        body: JSONObject,
        onChunk: suspend (GenerateContentChunk) -> Unit,
    ) {
        execWithRetry { access ->
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $access")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "text/event-stream")
                connectTimeout = 20_000
                readTimeout = 120_000
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = (conn.errorStream ?: conn.inputStream)
                    ?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }
                    .orEmpty()
                return@execWithRetry HttpResult(code, err)
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    // Code Assist wraps the response in {"response": {...}}
                    val inner = obj.optJSONObject("response") ?: obj
                    onChunk(GenerateContentChunk(inner))
                }
            }
            HttpResult(code, "")
        }
    }

    private suspend fun execWithRetry(call: suspend (access: String) -> HttpResult): String {
        var t = tokens()
        var r = call(t.accessToken)
        if (r.code == 401) {
            val refreshed = onRefreshNeeded()
                ?: throw RuntimeException("Code Assist 401 and refresh failed — re-login required")
            t = refreshed
            r = call(t.accessToken)
        }
        if (r.code !in 200..299) {
            throw RuntimeException("Code Assist HTTP ${r.code}: ${r.body.take(500)}")
        }
        return r.body
    }

    /** gemini-cli sends client metadata to identify itself. We do the same. */
    private fun clientMetadata(): JSONObject = JSONObject().apply {
        put("ideType", "ANDROID")
        put("platform", "ANDROID")
        put("pluginType", "GEMINI")
        put("ideVersion", "1.0.0")
    }

    private data class HttpResult(val code: Int, val body: String)

    companion object {
        const val BASE_URL = "https://cloudcode-pa.googleapis.com"
        const val API_VERSION = "v1internal"
    }
}

data class LoadCodeAssistResponse(
    val currentTier: String?,
    val allowedTiers: List<String>,
    val cloudaicompanionProject: String?,
) {
    val isOnboarded: Boolean get() = currentTier != null && !cloudaicompanionProject.isNullOrBlank()
}

data class OnboardUserResponse(
    val done: Boolean,
    val projectId: String?,
)

/**
 * Raw SSE chunk from `streamGenerateContent`. The JSON shape matches the
 * `GenerateContentResponse` used by the public Gemini API, so we can route
 * it through the same parser in [com.gemini.bridge.RestGeminiCore].
 */
data class GenerateContentChunk(val json: JSONObject) {
    fun candidates(): JSONArray? = json.optJSONArray("candidates")
    fun usageTokens(): Int = json.optJSONObject("usageMetadata")?.optInt("totalTokenCount", 0) ?: 0
}
