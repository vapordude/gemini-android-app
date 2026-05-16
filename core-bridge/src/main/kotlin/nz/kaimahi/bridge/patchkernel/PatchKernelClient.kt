package nz.kaimahi.bridge.patchkernel

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the **Patch Kernel** — a sidecar tool that ships separately
 * from this app. It exposes surgical file operations (hash-verified line
 * patches, multi-file atomic batches, chunked writes, symbol search, git
 * helpers) over an HTTP transport bound to `127.0.0.1:7979` by default.
 *
 * The kernel itself is proprietary tooling installed alongside the app
 * (typically inside Termux, or as a foreground service). The app talks to
 * it as an HTTP client so this repository never embeds the kernel source.
 * When the kernel isn't reachable, every method returns a [KernelError]
 * and the user-facing tool surfaces a clear "kernel not running" message
 * — the app remains fully functional without it, using only the built-in
 * file tools.
 *
 * Tool surface (matches the kernel's MCP method names verbatim so the
 * kernel can be addressed equivalently by Gemini CLI or by this app):
 *
 *   read_file_window, patch_file, write_file, multi_patch,
 *   batch_execute, search_symbols, search, chunk_start/append/commit/abort,
 *   git_status, git_diff, git_commit
 *
 * Auth: if [authToken] is non-blank it's sent as `Authorization: Bearer …`.
 * The kernel's `MCP_AUTH_TOKEN` env var must match.
 */
class PatchKernelClient(
    private val baseUrl: String = DEFAULT_URL,
    private val authToken: String? = null,
) {

    /** True iff the kernel responds 200 to /health within a short timeout. */
    fun isReachable(): Boolean = runCatching {
        val conn = (URL("$baseUrl/health").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 750
            readTimeout = 750
        }
        try { conn.responseCode == 200 } finally { conn.disconnect() }
    }.getOrDefault(false)

    /**
     * Call an MCP tool by name. Body is the tool's `arguments` JSON; result
     * is the kernel's response JSON. Throws [KernelError] on transport
     * failure or non-2xx status.
     */
    fun callTool(name: String, arguments: JSONObject): JSONObject {
        val body = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        val resp = post("$baseUrl/mcp", body)
        return resp
    }

    fun readFileWindow(path: String, startLine: Int, endLine: Int): JSONObject = callTool(
        "read_file_window",
        JSONObject().apply {
            put("path", path); put("start_line", startLine); put("end_line", endLine)
        },
    )

    fun patchFile(
        path: String,
        startLine: Int,
        endLine: Int,
        newContent: String,
        expectedHash: String,
    ): JSONObject = callTool(
        "patch_file",
        JSONObject().apply {
            put("path", path)
            put("start_line", startLine); put("end_line", endLine)
            put("new_content", newContent)
            put("expected_hash", expectedHash)
        },
    )

    fun writeFile(path: String, content: String): JSONObject = callTool(
        "write_file",
        JSONObject().apply { put("path", path); put("content", content) },
    )

    fun multiPatch(patches: JSONArray): JSONObject = callTool(
        "multi_patch",
        JSONObject().apply { put("patches", patches) },
    )

    fun searchSymbols(query: String, k: Int = 20): JSONObject = callTool(
        "search_symbols",
        JSONObject().apply { put("query", query); put("k", k) },
    )

    fun search(pattern: String, regex: Boolean = false): JSONObject = callTool(
        "search",
        JSONObject().apply { put("pattern", pattern); put("regex", regex) },
    )

    fun chunkStart(path: String): JSONObject = callTool(
        "chunk_start", JSONObject().apply { put("path", path) },
    )
    fun chunkAppend(sessionId: String, content: String): JSONObject = callTool(
        "chunk_append",
        JSONObject().apply { put("session_id", sessionId); put("content", content) },
    )
    fun chunkCommit(sessionId: String): JSONObject = callTool(
        "chunk_commit", JSONObject().apply { put("session_id", sessionId) },
    )
    fun chunkAbort(sessionId: String): JSONObject = callTool(
        "chunk_abort", JSONObject().apply { put("session_id", sessionId) },
    )

    fun gitStatus(): JSONObject = callTool("git_status", JSONObject())
    fun gitDiff(path: String? = null): JSONObject = callTool(
        "git_diff",
        JSONObject().apply { if (!path.isNullOrBlank()) put("path", path) },
    )
    fun gitCommit(message: String): JSONObject = callTool(
        "git_commit", JSONObject().apply { put("message", message) },
    )

    private fun post(url: String, body: JSONObject): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            if (!authToken.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
            connectTimeout = 5_000
            readTimeout = 30_000
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream
            else conn.errorStream ?: conn.inputStream
            val text = stream.use {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }
            if (code !in 200..299) {
                throw KernelError("Kernel HTTP $code: ${text.take(500)}")
            }
            return runCatching { JSONObject(text) }.getOrElse {
                throw KernelError("Kernel returned non-JSON body: ${text.take(200)}")
            }
        } catch (e: KernelError) {
            throw e
        } catch (e: Exception) {
            throw KernelError("Kernel unreachable at $baseUrl: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    class KernelError(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

    companion object {
        const val DEFAULT_URL = "http://127.0.0.1:7979"
    }
}
