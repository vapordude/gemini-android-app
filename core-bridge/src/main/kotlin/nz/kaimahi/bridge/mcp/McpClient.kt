package nz.kaimahi.bridge.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP client wired to one server. Holds the transport, an id
 * counter, and the cached tool list.
 *
 * Lifecycle: construct → `initialize()` → `listTools()` → repeated
 * `callTool()` as needed. No cleanup required for v1 (HTTP per-call
 * transport; nothing held open).
 */
internal class McpClient(
    val config: McpServerConfig,
    private val transport: McpHttpTransport = McpHttpTransport(config.url, config.authToken),
) {

    private val idGen = AtomicLong(1)

    @Volatile private var initialized = false
    @Volatile private var cachedTools: List<McpTool> = emptyList()

    suspend fun initialize(): JSONObject {
        val params = JSONObject()
            .put("protocolVersion", MCP_PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", MCP_CLIENT_INFO)
        val resp = call("initialize", params)
        initialized = true
        return resp
    }

    suspend fun listTools(): List<McpTool> {
        require(initialized) { "call initialize() first" }
        val resp = call("tools/list", null)
        val arr = resp.optJSONArray("tools") ?: JSONArray()
        val out = mutableListOf<McpTool>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            out.add(
                McpTool(
                    name = name,
                    description = o.optString("description"),
                    inputSchema = McpJsonRpc.toMap(o.optJSONObject("inputSchema")),
                )
            )
        }
        cachedTools = out
        return out
    }

    fun cachedToolList(): List<McpTool> = cachedTools

    suspend fun callTool(name: String, arguments: Map<String, Any?>): McpCallResult {
        require(initialized) { "call initialize() first" }
        val args = JSONObject()
        for ((k, v) in arguments) args.put(k, v ?: JSONObject.NULL)
        val params = JSONObject()
            .put("name", name)
            .put("arguments", args)
        val resp = call("tools/call", params)
        val isError = resp.optBoolean("isError", false)
        val content = resp.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "text" -> {
                    if (text.isNotEmpty()) text.append("\n")
                    text.append(item.optString("text"))
                }
                "image" -> {
                    if (text.isNotEmpty()) text.append("\n")
                    val mime = item.optString("mimeType", "image/*")
                    text.append("[image:$mime ${item.optString("data").length} bytes]")
                }
                else -> {
                    if (text.isNotEmpty()) text.append("\n")
                    text.append(item.toString())
                }
            }
        }
        return McpCallResult(isError = isError, text = text.toString())
    }

    private suspend fun call(method: String, params: JSONObject?): JSONObject {
        val id = idGen.getAndIncrement()
        val req = McpJsonRpc.request(id, method, params)
        val envelope = transport.call(req, id)
        return McpJsonRpc.parseResult(envelope, id)
    }
}
