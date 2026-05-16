package com.gemini.bridge.tools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.gemini.bridge.storage.SecurePrefs
import com.gemini.domain.ToolCall
import com.gemini.domain.ToolCallResult
import com.gemini.domain.ToolCategory
import com.gemini.domain.ToolSpec

/**
 * Read-only listing for the standard user directories the model can ask
 * about by name. Each scope (Home / Documents / Downloads) is reached via
 * a Storage Access Framework tree URI the user has granted in Settings
 * with persistable read permission. If no URI is recorded for the
 * requested scope, the tool returns a clear "not granted" error — it
 * never falls back to legacy `Environment.getExternalStorageDirectory()`
 * which doesn't work on Android 11+ without `MANAGE_EXTERNAL_STORAGE`.
 */
class ListUserFilesTool(
    private val context: Context,
    private val prefs: SecurePrefs = SecurePrefs(context),
) : Tool {
    override val spec = ToolSpec(
        name = "list_user_files",
        description = "List files in one of the standard user directories: Home, Documents, Downloads. Read-only. Use when the user mentions a file by name without giving a path. Each scope must be granted by the user via Settings before the tool can read it.",
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
        val rootUri = scopeUri(scope) ?: return@runCatching ToolOutput.error(
            call.id,
            "The \"$scope\" scope has not been granted. Open Settings → Storage " +
                "Access → Grant \"$scope\" to give the model read access.",
        )
        val root = DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
            ?: return@runCatching ToolOutput.error(
                call.id,
                "Could not open the granted tree URI for \"$scope\". " +
                    "Re-grant access via Settings.",
            )
        val target = if (sub.isNullOrBlank()) root else resolve(root, sub)
            ?: return@runCatching ToolOutput.error(
                call.id,
                "Subpath \"$sub\" was not found under \"$scope\".",
            )
        if (!target.isDirectory) {
            return@runCatching ToolOutput.error(call.id, "Not a directory: \"${target.name}\"")
        }
        val children = target.listFiles().sortedBy { it.name ?: "" }
        val out = buildString {
            append("Scope: ").append(scope)
            if (!sub.isNullOrBlank()) append(" / ").append(sub)
            append('\n')
            append("Entries: ").append(children.size).append("\n\n")
            for (c in children) {
                val name = c.name ?: "(unnamed)"
                val suffix = if (c.isDirectory) "/" else " (${c.length()} B)"
                append("  ").append(name).append(suffix).append('\n')
            }
        }
        ToolOutput.clamp(out, call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "list failed") }

    private fun scopeUri(name: String): String? = when (name.lowercase()) {
        "home" -> prefs.homeTreeUri
        "documents" -> prefs.documentsTreeUri
        "downloads" -> prefs.downloadsTreeUri
        else -> null
    }

    /**
     * Walk the slash-separated [sub]path from [root]. Rejects empty segments
     * and `..` so the model can't escape the granted scope.
     */
    private fun resolve(root: DocumentFile, sub: String): DocumentFile? {
        var cur: DocumentFile = root
        for (seg in sub.split('/')) {
            val s = seg.trim()
            if (s.isEmpty() || s == "." || s == "..") return null
            cur = cur.findFile(s) ?: return null
        }
        return cur
    }
}
