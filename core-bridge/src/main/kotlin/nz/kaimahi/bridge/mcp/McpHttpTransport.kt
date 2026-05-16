package nz.kaimahi.bridge.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * MCP Streamable-HTTP transport. The server endpoint takes a POST with a
 * JSON-RPC body and responds with either:
 *
 *   - `application/json` — a single JSON-RPC response (the simple case
 *     used by stateless servers).
 *   - `text/event-stream` — an SSE stream of `data:` frames, each one a
 *     JSON-RPC response. We collect frames until the matching response
 *     id arrives.
 *
 * Either way the client side is one logical request → one logical
 * response. Notifications + server-initiated calls land in a follow-up
 * (would need a long-poll loop and a state machine).
 *
 * Pure JDK — no external HTTP client. Keeps core-bridge dependency-light.
 */
internal class McpHttpTransport(
    private val baseUrl: String,
    private val authToken: String? = null,
    private val timeoutMs: Int = 30_000,
) {

    /** Send a JSON-RPC request envelope and return the matching response
     *  envelope. The caller passes the request id so we can match it in
     *  an SSE stream that might interleave server-initiated messages. */
    suspend fun call(request: JSONObject, expectedId: Long): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (!authToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
        }

        try {
            conn.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val contentType = conn.contentType?.lowercase().orEmpty()
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                ?: throw McpError.Transport("status $status, no body")

            when {
                contentType.startsWith("text/event-stream") -> readSseUntilId(stream, expectedId)
                else -> readSingleJson(stream)
            }
        } catch (e: McpError) {
            throw e
        } catch (e: Exception) {
            throw McpError.Transport("transport failed: ${e.message ?: e.javaClass.simpleName}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun readSingleJson(stream: java.io.InputStream): JSONObject {
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (text.isBlank()) throw McpError.Transport("empty body")
        return runCatching { JSONObject(text) }
            .getOrElse { throw McpError.Transport("non-JSON body: ${text.take(200)}") }
    }

    private fun readSseUntilId(stream: java.io.InputStream, expectedId: Long): JSONObject {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val dataBuf = StringBuilder()
        reader.forEachLine { line ->
            when {
                line.isEmpty() -> {
                    if (dataBuf.isNotEmpty()) {
                        val payload = dataBuf.toString()
                        dataBuf.clear()
                        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return@forEachLine
                        // Notifications have no id; skip them at this layer.
                        if (!obj.has("id")) return@forEachLine
                        if (obj.optLong("id", Long.MIN_VALUE) == expectedId) {
                            return obj
                        }
                    }
                }
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").trimStart()
                    if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                    dataBuf.append(payload)
                }
                // event: / id: / retry: lines are MCP-irrelevant for v1
                else -> {}
            }
        }
        throw McpError.Transport("SSE stream ended before id $expectedId arrived")
    }
}
