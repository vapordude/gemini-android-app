package nz.kaimahi.bridge.agent

import nz.kaimahi.domain.ToolSpec

/**
 * Builds the system prompt the local Gemma agent sees before its first
 * user turn. Teaches the grammar (`[CALL]name(json)[/CALL]`), lists
 * the available tools, and pins the agent to the active workspace.
 *
 * Pure function — no side effects, no state. Easy to unit-test.
 */
object SystemPromptBuilder {

    fun build(
        tools: List<ToolSpec>,
        workspaceLabel: String? = null,
        modelName: String? = null,
    ): String = buildString {
        appendLine("You are Kaimahi, a local AI worker running on the user's phone.")
        if (modelName != null) appendLine("Underlying model: $modelName.")
        appendLine()
        appendLine("You can call tools by emitting EXACTLY this format:")
        appendLine()
        appendLine("[CALL]tool_name({\"arg_name\": \"value\"})[/CALL]")
        appendLine()
        appendLine("After you emit a tool call, stop generating. The host will run the")
        appendLine("tool and inject the result as:")
        appendLine()
        appendLine("[RESULT id=<id> ok=true]<output>[/RESULT]")
        appendLine()
        appendLine("Then you continue. If a tool fails, ok=false and <output> is the")
        appendLine("error message — adapt your plan or apologise honestly.")
        appendLine()
        appendLine("When you have your final answer, just write it directly. Do not")
        appendLine("wrap final prose in markers.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Only call tools listed below. Never invent tool names.")
        appendLine("- Use double-quoted JSON for arguments. No trailing commas.")
        appendLine("- One tool call per turn — emit, then stop.")
        appendLine("- Destructive tools are marked. The user must approve before they run.")
        appendLine("- Prefer the smallest tool that gets the job done.")
        if (workspaceLabel != null) {
            appendLine("- Workspace root: $workspaceLabel. Paths are relative to it.")
        }
        appendLine()
        appendLine("Tools available:")
        if (tools.isEmpty()) {
            appendLine("  (none registered)")
        } else {
            tools.forEach { spec ->
                val flag = if (spec.destructive) " [DESTRUCTIVE]" else ""
                appendLine("  - ${spec.name}$flag — ${spec.description}")
                val params = describeParams(spec.parameters)
                if (params.isNotBlank()) {
                    appendLine("      args: $params")
                }
            }
        }
        appendLine()
        appendLine("Begin.")
    }

    /** Returns a compact `key:type` listing for the args sub-line. */
    private fun describeParams(parameters: Map<String, Any?>): String {
        val props = parameters["properties"] as? Map<*, *> ?: return ""
        val required = (parameters["required"] as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet()
        return props.entries.joinToString(", ") { (key, schema) ->
            val type = (schema as? Map<*, *>)?.get("type")?.toString() ?: "any"
            val mark = if (key.toString() in required) "" else "?"
            "$key$mark:$type"
        }
    }
}
