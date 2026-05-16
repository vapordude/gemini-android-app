package nz.kaimahi.bridge.mcp

import nz.kaimahi.bridge.tools.Tool
import nz.kaimahi.bridge.tools.ToolOutput
import nz.kaimahi.domain.ToolCall
import nz.kaimahi.domain.ToolCallResult
import nz.kaimahi.domain.ToolCategory
import nz.kaimahi.domain.ToolSpec

/**
 * Adapts an MCP-discovered tool into Kaimahi's `Tool` interface so it
 * can be registered in the same `ToolRegistry` cloud function-calling
 * and the local agent loop both read from.
 *
 * Tool names are namespaced with the server id (`<server>__<tool>`) so
 * two MCP servers exposing a `read_file` don't collide with each
 * other or with the built-in tools.
 */
internal class McpRemoteTool(
    private val client: McpClient,
    private val mcpTool: McpTool,
) : Tool {

    private val qualifiedName: String = "${client.config.id}__${mcpTool.name}"

    override val spec: ToolSpec = ToolSpec(
        name = qualifiedName,
        description = mcpTool.description.ifBlank { "MCP tool from ${client.config.displayName}" },
        category = ToolCategory.OTHER,
        // MCP doesn't currently advertise a destructive flag on tools.
        // Default-safe: any MCP tool gates through the approval dialog
        // until the protocol grows a hint or the user opts in per
        // server. (Less surprising for users running unknown servers.)
        destructive = true,
        parameters = remapSchema(mcpTool.inputSchema),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult {
        return try {
            val result = client.callTool(mcpTool.name, call.arguments)
            if (result.isError) {
                ToolOutput.error(call.id, result.text.ifBlank { "MCP tool reported an error" })
            } else {
                ToolOutput.clamp(result.text, call.id)
            }
        } catch (e: McpError.Server) {
            ToolOutput.error(call.id, "MCP server: ${e.message}")
        } catch (e: McpError.Transport) {
            ToolOutput.error(call.id, "MCP transport: ${e.message}")
        } catch (e: McpError.Protocol) {
            ToolOutput.error(call.id, "MCP protocol: ${e.message}")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ToolOutput.error(call.id, "MCP error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** MCP servers return JSON-Schema-flavour input schemas. The local
     *  `Tool` system expects a JSON-Schema-like map too (see
     *  `nz.kaimahi.bridge.tools.SchemaHelpers`), so the shape is
     *  largely pass-through. We only normalise `required` if it's
     *  absent. */
    private fun remapSchema(input: Map<String, Any?>): Map<String, Any?> {
        if (input.isEmpty()) {
            return mapOf("type" to "object", "properties" to emptyMap<String, Any?>(), "required" to emptyList<String>())
        }
        val out = input.toMutableMap()
        if (out["type"] == null) out["type"] = "object"
        if (out["properties"] == null) out["properties"] = emptyMap<String, Any?>()
        if (out["required"] == null) out["required"] = emptyList<String>()
        return out
    }
}
