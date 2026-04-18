package com.gemini.bridge

import android.util.Log
import com.gemini.domain.GeminiCore
import com.gemini.domain.GeminiMessage
import com.gemini.domain.GeminiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Direct REST client for the Gemini Generative Language API.
 *
 * This is the path used when the user supplies an API key — it calls
 * `generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`
 * and keeps a rolling conversation history so multi-turn chat works.
 */
class RestGeminiCore(
    private val defaultModel: String = "gemini-1.5-flash-latest"
) : GeminiCore {

    private var apiKey: String = ""
    private var model: String = defaultModel
    private val history = mutableListOf<GeminiMessage>()

    override suspend fun init(config: Map<String, Any>): GeminiResult {
        apiKey = (config["api_key"] ?: config["apiKey"] ?: "").toString().trim()
        model = (config["model"] as? String)?.ifBlank { defaultModel } ?: defaultModel
        return if (apiKey.isBlank()) GeminiResult.Error("API key is required")
        else GeminiResult.Success("Ready")
    }

    override suspend fun setProjectFolder(uri: String): GeminiResult =
        GeminiResult.Success("Project folder set to $uri")

    override suspend fun sendMessage(text: String): GeminiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext GeminiResult.Error("API key not configured")

        val userMsg = GeminiMessage(
            id = System.currentTimeMillis().toString(),
            text = text,
            isUser = true,
            timestamp = System.currentTimeMillis()
        )
        history.add(userMsg)

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$model:generateContent?key=${URLEncoder.encode(apiKey, "UTF-8")}"
            val (code, body) = postJson(url, buildPayload())

            if (code !in 200..299) {
                val msg = tryExtractError(body) ?: "HTTP $code"
                // Remove the user message so the UI doesn't show a dangling
                // prompt with no reply; the caller will surface the error.
                history.remove(userMsg)
                return@withContext GeminiResult.Error(msg)
            }

            val reply = extractReply(body)
            if (reply.isNullOrBlank()) {
                history.remove(userMsg)
                return@withContext GeminiResult.Error("Gemini returned an empty response")
            }

            val aiMsg = GeminiMessage(
                id = (System.currentTimeMillis() + 1).toString(),
                text = reply,
                isUser = false,
                timestamp = System.currentTimeMillis()
            )
            history.add(aiMsg)
            GeminiResult.Success(reply)
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
            history.remove(userMsg)
            GeminiResult.Error(e.message ?: "Network error")
        }
    }

    override suspend fun resetSession(): GeminiResult {
        history.clear()
        return GeminiResult.Success("Reset")
    }

    override suspend fun loadHistory(): List<GeminiMessage> = history.toList()

    private fun buildPayload(): String {
        val contents = JSONArray()
        history.forEach { m ->
            contents.put(
                JSONObject()
                    .put("role", if (m.isUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.text)))
            )
        }
        return JSONObject().put("contents", contents).toString()
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

    private fun extractReply(body: String): String? {
        return try {
            val json = JSONObject(body)
            val candidates = json.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            buildString {
                for (i in 0 until parts.length()) {
                    append(parts.getJSONObject(i).optString("text"))
                }
            }.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun tryExtractError(body: String): String? = try {
        JSONObject(body).optJSONObject("error")?.optString("message")
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val TAG = "RestGeminiCore"
    }
}
