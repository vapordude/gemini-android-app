package com.gemini.bridge.tools

import android.content.Context
import android.os.Environment
import com.gemini.domain.ToolCall
import com.gemini.domain.ToolCallResult
import com.gemini.domain.ToolCategory
import com.gemini.domain.ToolSpec
import java.io.File

/**
 * Surfaces the standard Android user directories — Home, Documents,
 * Downloads — to the model. Pure read access; the model can list and
 * read files but not write outside the project workspace.
 *
 * Why a separate tool instead of folding into `list_directory`/`read_file`:
 * those tools operate against the user-picked workspace tree (project
 * folder). This one operates against the platform-defined directories
 * that exist independently of which project is open.
 */
class ListUserFilesTool(private val context: Context) : Tool {
    override val spec = ToolSpec(
        name = "list_user_files",
        description = "List files in one of the standard user directories: Home, Documents, Downloads. Read-only. Use when the user mentions a file by name without giving a path.",
        category = ToolCategory.FILES,
        destructive = false,
        parameters = objectParams(
            "scope" to mapOf(
                "type" to "string",
                "description" to "Which user directory to list: \"home\", \"documents\", or \"downloads\".",
                "enum" to listOf("home", "documents", "downloads"),
            ),
            "subpath" to stringProp("Optional subdirectory under the scope. Empty for the root."),
            required = listOf("scope"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val scope = call.arguments["scope"] as? String ?: error("scope is required")
        val sub = call.arguments["subpath"] as? String
        val base = resolveScope(scope)
            ?: return@runCatching ToolOutput.error(call.id, "Unknown scope: $scope")
        val target = if (sub.isNullOrBlank()) base else File(base, sub)
        if (!within(base, target)) {
            return@runCatching ToolOutput.error(call.id, "Path escapes scope root")
        }
        if (!target.exists()) {
            return@runCatching ToolOutput.error(call.id, "Directory does not exist: ${target.absolutePath}")
        }
        if (!target.isDirectory) {
            return@runCatching ToolOutput.error(call.id, "Not a directory: ${target.absolutePath}")
        }
        val children = target.listFiles()?.sortedBy { it.name } ?: emptyList()
        val out = buildString {
            append("Directory: ").append(target.absolutePath).append("\n")
            append("Entries: ").append(children.size).append("\n\n")
            for (c in children) {
                val suffix = if (c.isDirectory) "/" else " (${c.length()} B)"
                append("  ").append(c.name).append(suffix).append("\n")
            }
        }
        ToolOutput.clamp(out, call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "list failed") }

    private fun resolveScope(name: String): File? = when (name.lowercase()) {
        "home" -> Environment.getExternalStorageDirectory()
        "documents" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        "downloads" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        else -> null
    }

    private fun within(base: File, target: File): Boolean = runCatching {
        target.canonicalPath.startsWith(base.canonicalPath)
    }.getOrDefault(false)
}
