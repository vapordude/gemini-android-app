package nz.kaimahi.bridge.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON-RPC 2.0 encoding for MCP messages. Stays as a small, focused
 * helper rather than pulling in a JSON-RPC library — keeps core-bridge
 * dependency-light and easy to audit.
 */
internal object McpJsonRpc {

    /** Build a JSON-RPC request envelope. */
    fun request(id: Long, method: String, params: JSONObject? = null): JSONObject {
        val obj = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) obj.put("params", params)
        return obj
    }

    /** Build a notification (no id, no response expected). */
    fun notification(method: String, params: JSONObject? = null): JSONObject {
        val obj = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
        if (params != null) obj.put("params", params)
        return obj
    }

    /** Parse the result field from a response. Throws McpError on protocol
     *  violations or server-reported errors. */
    fun parseResult(envelope: JSONObject, expectedId: Long): JSONObject {
        if (envelope.optString("jsonrpc") != "2.0") {
            throw McpError.Protocol("expected jsonrpc=2.0, got '${envelope.optString("jsonrpc")}'")
        }
        if (envelope.has("error")) {
            val err = envelope.optJSONObject("error") ?: throw McpError.Protocol("malformed error")
            throw McpError.Server(err.optInt("code", -1), err.optString("message"))
        }
        val id = envelope.optLong("id", -1L)
        if (id != expectedId) {
            throw McpError.Protocol("id mismatch: expected $expectedId, got $id")
        }
        return envelope.optJSONObject("result")
            ?: throw McpError.Protocol("response missing result")
    }

    /** Convert a JSONObject sub-tree into a plain Kotlin Map for tool args. */
    fun toMap(obj: JSONObject?): Map<String, Any?> {
        if (obj == null) return emptyMap()
        val out = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            out[key] = when (val v = obj.opt(key)) {
                JSONObject.NULL -> null
                is JSONObject -> toMap(v)
                is JSONArray -> (0 until v.length()).map { v.opt(it) }
                else -> v
            }
        }
        return out
    }
}
