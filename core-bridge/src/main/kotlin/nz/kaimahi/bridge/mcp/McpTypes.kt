package nz.kaimahi.bridge.mcp

import org.json.JSONObject

/**
 * Model Context Protocol (MCP) — Anthropic's open spec for AI ↔ tool
 * server communication. The wire format is JSON-RPC 2.0; v1 ships the
 * HTTP+SSE transport (POST for client→server, SSE for server→client).
 *
 * v1 scope here: `initialize`, `tools/list`, `tools/call`. The
 * resources / prompts / notifications surface lands in a follow-up.
 *
 * Spec: https://modelcontextprotocol.io
 */

/** Persisted configuration for an MCP server the user has added. */
data class McpServerConfig(
    val id: String,
    val displayName: String,
    /** SSE endpoint base URL. The protocol POSTs to `<url>` for client→server
     *  and opens an SSE connection at the response stream. */
    val url: String,
    val authToken: String? = null,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("url", url)
        .also { obj ->
            if (authToken != null) obj.put("authToken", authToken)
            obj.put("enabled", enabled)
        }

    companion object {
        fun fromJson(o: JSONObject): McpServerConfig? {
            val id = o.optString("id").ifBlank { return null }
            val name = o.optString("displayName").ifBlank { id }
            val url = o.optString("url").ifBlank { return null }
            return McpServerConfig(
                id = id,
                displayName = name,
                url = url,
                authToken = o.optString("authToken").takeIf { it.isNotBlank() },
                enabled = o.optBoolean("enabled", true),
            )
        }
    }
}

/** Tool exposed by an MCP server, as returned by `tools/list`. */
data class McpTool(
    val name: String,
    val description: String,
    /** JSON-Schema-like map describing the tool's input arguments. */
    val inputSchema: Map<String, Any?>,
)

/** Result of an MCP `tools/call`. */
data class McpCallResult(
    val isError: Boolean,
    /** Stringified content. MCP supports multi-part content (text / image /
     *  resource); for v1 we flatten everything into a single string —
     *  enough for the local agent loop to thread back into the prompt. */
    val text: String,
)

/** Errors surfaced through the MCP client. */
sealed class McpError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class Transport(message: String, cause: Throwable? = null) : McpError(message, cause)
    class Protocol(message: String) : McpError(message)
    class Server(val code: Int, message: String) : McpError("$code: $message")
}

/** MCP protocol version we negotiate. */
internal const val MCP_PROTOCOL_VERSION: String = "2024-11-05"

/** Client info advertised on `initialize`. */
internal val MCP_CLIENT_INFO: JSONObject = JSONObject()
    .put("name", "kaimahi")
    .put("version", "1.0.0")
