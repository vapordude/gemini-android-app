package com.gemini.bridge.tools

import com.gemini.bridge.memory.MemoryStore
import com.gemini.domain.ToolCall
import com.gemini.domain.ToolCallResult
import com.gemini.domain.ToolCategory
import com.gemini.domain.ToolSpec

class RememberFactTool(private val memory: MemoryStore) : Tool {
    override val spec = ToolSpec(
        name = "remember_fact",
        description = "Save a short, structured fact about the user that should persist across sessions and be visible to future turns (e.g. name, role, preferences). Use sparingly — only for stable facts, not transient context.",
        category = ToolCategory.MEMORY,
        destructive = false,
        parameters = objectParams(
            "key" to stringProp("Short identifier for the fact (e.g. \"name\", \"prefers_language\")"),
            "value" to stringProp("Fact body in natural language"),
            required = listOf("key", "value"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val key = call.arguments["key"] as? String ?: error("key is required")
        val value = call.arguments["value"] as? String ?: error("value is required")
        memory.setFact(key, value)
        ToolOutput.clamp("Remembered: $key = $value", call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "remember failed") }
}

class ForgetFactTool(private val memory: MemoryStore) : Tool {
    override val spec = ToolSpec(
        name = "forget_fact",
        description = "Delete a previously-remembered fact by key.",
        category = ToolCategory.MEMORY,
        destructive = true,
        parameters = stringParam("key", "Key of the fact to forget"),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val key = call.arguments["key"] as? String ?: error("key is required")
        memory.deleteFact(key)
        ToolOutput.clamp("Forgot: $key", call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "forget failed") }
}

class RecallMemoryTool(private val memory: MemoryStore) : Tool {
    override val spec = ToolSpec(
        name = "recall_memory",
        description = "Search saved notes by keyword and return the top-k most relevant excerpts. Use when the user references something that should be in long-term memory but isn't in the current context.",
        category = ToolCategory.MEMORY,
        destructive = false,
        parameters = objectParams(
            "query" to stringProp("Free-form keywords describing what to recall"),
            "k" to mapOf(
                "type" to "integer",
                "description" to "Max number of notes to return (default 5, max 10)",
            ),
            required = listOf("query"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val q = call.arguments["query"] as? String ?: error("query is required")
        val k = (call.arguments["k"] as? Number)?.toInt()?.coerceIn(1, 10) ?: 5
        val hits = memory.recall(q, k)
        if (hits.isEmpty()) return@runCatching ToolOutput.clamp("(no relevant notes found)", call.id)
        val out = buildString {
            for (h in hits) {
                append("# ").append(h.title).append("  [score=")
                append("%.2f".format(h.score)).append(", id=").append(h.id).append("]\n")
                append(h.snippet).append("\n\n")
            }
        }
        ToolOutput.clamp(out, call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "recall failed") }
}

class NoteWriteTool(private val memory: MemoryStore) : Tool {
    override val spec = ToolSpec(
        name = "note_write",
        description = "Save a free-form markdown note to long-term memory. Use for context too long for a fact (e.g. project conventions, ongoing tasks, summaries of past conversations). Retrievable later via `recall_memory`.",
        category = ToolCategory.MEMORY,
        destructive = false,
        parameters = objectParams(
            "title" to stringProp("Short title — appears as the note's heading and in recall results"),
            "content" to stringProp("Markdown body"),
            required = listOf("title", "content"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val title = call.arguments["title"] as? String ?: error("title is required")
        val content = call.arguments["content"] as? String ?: error("content is required")
        val id = memory.writeNote(title, content)
        ToolOutput.clamp("Saved note: \"$title\" (id=$id)", call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "note write failed") }
}
