package nz.kaimahi.bridge.tools

import nz.kaimahi.bridge.patchkernel.PatchKernelClient
import nz.kaimahi.domain.ToolCall
import nz.kaimahi.domain.ToolCallResult
import nz.kaimahi.domain.ToolCategory
import nz.kaimahi.domain.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools that delegate to the external **Patch Kernel** sidecar. Each
 * runs against `localhost:7979` by default; when the kernel isn't
 * running, the tool returns a clear error and the rest of the app keeps
 * working with the built-in file tools.
 *
 * Why a separate tool family rather than overriding `write_file`/`edit_file`:
 * the kernel guarantees properties the built-in tools can't (hash-verified
 * patching, multi-file atomicity, chunk sessions for streaming writes,
 * symbol-aware search). Surfacing them as their own names lets the model
 * choose precisely — and lets the user disable the kernel without changing
 * the basic file editing behaviour.
 */

class KernelPatchFileTool(private val client: PatchKernelClient) : Tool {
    override val spec = ToolSpec(
        name = "kernel_patch_file",
        description = "Surgically replace lines [start_line..end_line] of `path` with `new_content`. Requires `expected_hash` matching the file's current SHA-256 to detect concurrent edits. Atomic; rolls back on hash mismatch.",
        category = ToolCategory.FILES,
        destructive = true,
        parameters = objectParams(
            "path" to stringProp("Repo-relative path of the file to patch"),
            "start_line" to mapOf("type" to "integer", "description" to "1-based inclusive start line"),
            "end_line" to mapOf("type" to "integer", "description" to "1-based inclusive end line"),
            "new_content" to stringProp("Replacement text; line endings preserved by the kernel"),
            "expected_hash" to stringProp("SHA-256 of the file as the agent last saw it"),
            required = listOf("path", "start_line", "end_line", "new_content", "expected_hash"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val a = call.arguments
        val path = a["path"] as? String ?: error("path is required")
        val start = (a["start_line"] as? Number)?.toInt() ?: error("start_line is required")
        val end = (a["end_line"] as? Number)?.toInt() ?: error("end_line is required")
        val content = a["new_content"] as? String ?: error("new_content is required")
        val hash = a["expected_hash"] as? String ?: error("expected_hash is required")
        val resp = client.patchFile(path, start, end, content, hash)
        ToolOutput.clamp(resp.toString(2), call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "kernel_patch_file failed") }
}

class KernelMultiPatchTool(private val client: PatchKernelClient) : Tool {
    override val spec = ToolSpec(
        name = "kernel_multi_patch",
        description = "Apply multiple file patches atomically — the kernel rolls back all of them if any one fails (hash mismatch or write error). `patches` is a JSON array of {path, start_line, end_line, new_content, expected_hash} objects.",
        category = ToolCategory.FILES,
        destructive = true,
        parameters = objectParams(
            "patches" to mapOf(
                "type" to "array",
                "description" to "Array of patch objects",
                "items" to mapOf("type" to "object"),
            ),
            required = listOf("patches"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val raw = call.arguments["patches"]
            ?: return@runCatching ToolOutput.error(call.id, "patches array is required")
        val arr = when (raw) {
            is JSONArray -> raw
            is List<*> -> JSONArray(raw)
            else -> JSONArray(raw.toString())
        }
        val resp = client.multiPatch(arr)
        ToolOutput.clamp(resp.toString(2), call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "kernel_multi_patch failed") }
}

class KernelSearchSymbolsTool(private val client: PatchKernelClient) : Tool {
    override val spec = ToolSpec(
        name = "kernel_search_symbols",
        description = "Symbol-aware search across the indexed codebase. Returns top-k function/class/struct definitions matching the query. Faster and more precise than grep when the user is asking about a named symbol.",
        category = ToolCategory.SEARCH,
        destructive = false,
        parameters = objectParams(
            "query" to stringProp("Symbol name fragment, case-insensitive"),
            "k" to mapOf("type" to "integer", "description" to "Max results (default 20)"),
            required = listOf("query"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val q = call.arguments["query"] as? String ?: error("query is required")
        val k = (call.arguments["k"] as? Number)?.toInt() ?: 20
        val resp = client.searchSymbols(q, k)
        ToolOutput.clamp(resp.toString(2), call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "kernel_search_symbols failed") }
}

class KernelReadWindowTool(private val client: PatchKernelClient) : Tool {
    override val spec = ToolSpec(
        name = "kernel_read_window",
        description = "Read a precise line window of a file plus the SHA-256 hash needed for a subsequent patch. Use before `kernel_patch_file` to ensure the hash you supply matches what the kernel sees.",
        category = ToolCategory.FILES,
        destructive = false,
        parameters = objectParams(
            "path" to stringProp("Repo-relative path"),
            "start_line" to mapOf("type" to "integer", "description" to "1-based inclusive start"),
            "end_line" to mapOf("type" to "integer", "description" to "1-based inclusive end"),
            required = listOf("path", "start_line", "end_line"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val path = call.arguments["path"] as? String ?: error("path is required")
        val start = (call.arguments["start_line"] as? Number)?.toInt() ?: error("start_line is required")
        val end = (call.arguments["end_line"] as? Number)?.toInt() ?: error("end_line is required")
        val resp = client.readFileWindow(path, start, end)
        ToolOutput.clamp(resp.toString(2), call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "kernel_read_window failed") }
}

class KernelChunkWriteTool(private val client: PatchKernelClient) : Tool {
    override val spec = ToolSpec(
        name = "kernel_chunk_write",
        description = "Write a large file via a chunked session. The kernel buffers the chunks and only writes the final file atomically on `commit=true`. Use when the file is bigger than fits in one response or when streaming is needed. Workflow: call with `op=start`, then `op=append` repeatedly, then `op=commit`. `op=abort` cancels.",
        category = ToolCategory.FILES,
        destructive = true,
        parameters = objectParams(
            "op" to mapOf(
                "type" to "string",
                "description" to "Operation step",
                "enum" to listOf("start", "append", "commit", "abort"),
            ),
            "path" to stringProp("Repo-relative path (for op=start)"),
            "session_id" to stringProp("Session id returned by op=start (for append/commit/abort)"),
            "content" to stringProp("Body chunk (for op=append)"),
            required = listOf("op"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val op = call.arguments["op"] as? String ?: error("op is required")
        val resp = when (op) {
            "start" -> client.chunkStart(call.arguments["path"] as? String ?: error("path required"))
            "append" -> client.chunkAppend(
                call.arguments["session_id"] as? String ?: error("session_id required"),
                call.arguments["content"] as? String ?: error("content required"),
            )
            "commit" -> client.chunkCommit(
                call.arguments["session_id"] as? String ?: error("session_id required"),
            )
            "abort" -> client.chunkAbort(
                call.arguments["session_id"] as? String ?: error("session_id required"),
            )
            else -> JSONObject().apply { put("error", "unknown op: $op") }
        }
        ToolOutput.clamp(resp.toString(2), call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "kernel_chunk_write failed") }
}

class KernelGitStatusTool(private val client: PatchKernelClient) : Tool {
    override val spec = ToolSpec(
        name = "kernel_git_status",
        description = "Get the repository's git status via the kernel.",
        category = ToolCategory.OTHER,
        destructive = false,
        parameters = mapOf("type" to "object", "properties" to emptyMap<String, Any>()),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        ToolOutput.clamp(client.gitStatus().toString(2), call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "kernel_git_status failed") }
}

class KernelGitDiffTool(private val client: PatchKernelClient) : Tool {
    override val spec = ToolSpec(
        name = "kernel_git_diff",
        description = "Get the git diff for the repo or a specific file via the kernel.",
        category = ToolCategory.OTHER,
        destructive = false,
        parameters = objectParams(
            "path" to stringProp("Optional file path; omit for whole-repo diff"),
            required = emptyList(),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val path = call.arguments["path"] as? String
        ToolOutput.clamp(client.gitDiff(path).toString(2), call.id)
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "kernel_git_diff failed") }
}
