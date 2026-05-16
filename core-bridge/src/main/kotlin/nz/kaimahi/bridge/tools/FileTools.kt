package nz.kaimahi.bridge.tools

import nz.kaimahi.bridge.workspace.Workspace
import nz.kaimahi.domain.ToolCall
import nz.kaimahi.domain.ToolCallResult
import nz.kaimahi.domain.ToolCategory
import nz.kaimahi.domain.ToolSpec

class ReadFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "read_file",
        description = "Read the contents of a UTF-8 text file inside the workspace.",
        category = ToolCategory.FILES,
        destructive = false,
        parameters = stringParam("path", "Relative path inside the workspace")
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = try {
        val path = call.arguments["path"] as? String ?: error("path is required")
        ToolOutput.clamp(ws.read(path), call.id)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ToolOutput.error(call.id, e.message ?: "read failed")
    }
}

class WriteFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "write_file",
        description = "Create or overwrite a text file inside the workspace.",
        category = ToolCategory.FILES,
        destructive = true,
        parameters = objectParams(
            "path" to stringProp("Relative path inside the workspace"),
            "content" to stringProp("Full UTF-8 contents to write"),
            required = listOf("path", "content")
        )
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = try {
        val path = call.arguments["path"] as? String ?: error("path is required")
        val content = call.arguments["content"] as? String ?: error("content is required")
        val before = try { ws.read(path) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
        val entry = ws.write(path, content)
        val diff = Diff.of(before.orEmpty(), content, entry.path)
        val header = if (before == null) "Created ${entry.path} (${entry.size} B)"
                     else "Updated ${entry.path} (${entry.size} B)"
        ToolOutput.clamp("$header\n$diff", call.id)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ToolOutput.error(call.id, e.message ?: "write failed")
    }
}

class EditFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "edit_file",
        description = "Replace a literal string inside a workspace file. Fails if " +
            "`old` is not unique unless `replace_all` is true.",
        category = ToolCategory.FILES,
        destructive = true,
        parameters = objectParams(
            "path" to stringProp("Relative path of the file"),
            "old" to stringProp("Exact text to replace"),
            "new" to stringProp("Replacement text"),
            "replace_all" to booleanProp("Replace all occurrences"),
            required = listOf("path", "old", "new")
        )
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = try {
        val path = call.arguments["path"] as? String ?: error("path is required")
        val old = call.arguments["old"] as? String ?: error("old is required")
        val new = call.arguments["new"] as? String ?: error("new is required")
        val replaceAll = (call.arguments["replace_all"] as? Boolean) ?: false
        val current = ws.read(path)
        val count = current.split(old).size - 1
        if (count == 0) error("old string not found in $path")
        if (count > 1 && !replaceAll) error("old string is not unique ($count matches); set replace_all=true")
        val updated = if (replaceAll) current.replace(old, new)
            else current.replaceFirst(Regex.escape(old).toRegex(), Regex.escapeReplacement(new))
        ws.write(path, updated)
        val diff = Diff.of(current, updated, path)
        ToolOutput.clamp("Replaced $count occurrence(s) in $path\n$diff", call.id)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ToolOutput.error(call.id, e.message ?: "edit failed")
    }
}

class ListDirTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "list_directory",
        description = "List the files and sub-directories at a workspace path.",
        category = ToolCategory.FILES,
        destructive = false,
        parameters = objectParams(
            "path" to stringProp("Relative directory path (empty for workspace root)"),
            required = emptyList()
        )
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = try {
        val path = (call.arguments["path"] as? String).orEmpty()
        val entries = ws.list(path)
        val rendered = if (entries.isEmpty()) "(empty)"
        else entries.joinToString("\n") {
            val suffix = if (it.isDir) "/" else " (${it.size} B)"
            "${it.name}$suffix"
        }
        ToolOutput.clamp(rendered, call.id)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ToolOutput.error(call.id, e.message ?: "list failed")
    }
}

class DeleteFileTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "delete_file",
        description = "Delete a file or empty directory inside the workspace.",
        category = ToolCategory.FILES,
        destructive = true,
        parameters = stringParam("path", "Relative path to delete")
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = try {
        val path = call.arguments["path"] as? String ?: error("path is required")
        val ok = ws.delete(path)
        if (ok) ToolOutput.clamp("Deleted $path", call.id)
        else ToolOutput.error(call.id, "Failed to delete $path")
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ToolOutput.error(call.id, e.message ?: "delete failed")
    }
}

// ---- Gemini parameter-schema helpers ----

internal fun stringProp(description: String) = mapOf(
    "type" to "string",
    "description" to description
)

internal fun booleanProp(description: String) = mapOf(
    "type" to "boolean",
    "description" to description
)

internal fun stringParam(name: String, description: String): Map<String, Any?> = mapOf(
    "type" to "object",
    "properties" to mapOf(name to stringProp(description)),
    "required" to listOf(name)
)

internal fun objectParams(vararg pairs: Pair<String, Map<String, Any?>>, required: List<String>): Map<String, Any?> =
    mapOf(
        "type" to "object",
        "properties" to pairs.toMap(),
        "required" to required
    )
