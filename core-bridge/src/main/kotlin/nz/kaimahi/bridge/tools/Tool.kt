package nz.kaimahi.bridge.tools

import nz.kaimahi.domain.ToolCall
import nz.kaimahi.domain.ToolCallResult
import nz.kaimahi.domain.ToolSpec

interface Tool {
    val spec: ToolSpec
    suspend fun execute(call: ToolCall): ToolCallResult
}

class ToolRegistry(private val tools: MutableMap<String, Tool> = mutableMapOf()) {
    fun register(tool: Tool) { tools[tool.spec.name] = tool }
    fun specs(): List<ToolSpec> = tools.values.map { it.spec }
    fun get(name: String): Tool? = tools[name]
    fun all(): Collection<Tool> = tools.values
}

internal object ToolOutput {
    const val MAX = 16_000

    fun clamp(result: String, callId: String): ToolCallResult {
        val ok = true
        return if (result.length <= MAX) ToolCallResult(callId, ok, result, false)
        else ToolCallResult(callId, ok, result.substring(0, MAX) + "\n…(truncated)", true)
    }

    fun error(callId: String, message: String): ToolCallResult =
        ToolCallResult(callId, false, message, false)
}
