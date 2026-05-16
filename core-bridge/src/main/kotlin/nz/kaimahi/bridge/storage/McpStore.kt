package nz.kaimahi.bridge.storage

import android.content.Context
import nz.kaimahi.bridge.mcp.McpServerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the user's MCP server list under `<filesDir>/mcp.json`. The
 * shape is intentionally simple JSON so it can be hand-edited via the
 * file system if needed and surveyed by the local agent during
 * troubleshooting.
 */
class McpStore(context: Context) {

    private val file: File = File(context.filesDir, "mcp.json")

    fun load(): List<McpServerConfig> {
        val obj = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        val arr = obj.optJSONArray("servers") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            McpServerConfig.fromJson(arr.optJSONObject(i) ?: return@mapNotNull null)
        }
    }

    fun save(servers: List<McpServerConfig>) {
        val arr = JSONArray()
        servers.forEach { arr.put(it.toJson()) }
        val root = JSONObject().put("servers", arr)
        runCatching { file.writeText(root.toString()) }
    }

    fun upsert(server: McpServerConfig) {
        val current = load().toMutableList()
        val idx = current.indexOfFirst { it.id == server.id }
        if (idx >= 0) current[idx] = server else current.add(server)
        save(current)
    }

    fun remove(id: String) {
        save(load().filterNot { it.id == id })
    }
}
