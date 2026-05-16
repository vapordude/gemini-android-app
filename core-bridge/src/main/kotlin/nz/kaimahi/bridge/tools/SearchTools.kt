package nz.kaimahi.bridge.tools

import nz.kaimahi.bridge.workspace.Workspace
import nz.kaimahi.domain.ToolCall
import nz.kaimahi.domain.ToolCallResult
import nz.kaimahi.domain.ToolCategory
import nz.kaimahi.domain.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GlobTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "glob",
        description = "Find files whose relative path matches a glob (e.g. `**/*.kt`).",
        category = ToolCategory.SEARCH,
        destructive = false,
        parameters = objectParams(
            "pattern" to stringProp("Glob pattern (supports **, *, ?)"),
            "path" to stringProp("Relative directory to search (default: workspace root)"),
            required = listOf("pattern")
        )
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = try {
        val pattern = call.arguments["pattern"] as? String ?: error("pattern is required")
        val base = (call.arguments["path"] as? String).orEmpty()
        val regex = globToRegex(pattern)
        val matches = withContext(Dispatchers.IO) {
            ws.walk(base).filter { !it.isDir && regex.matches(it.path) }
                .take(500)
                .map { it.path }
                .toList()
        }
        if (matches.isEmpty()) ToolOutput.clamp("(no matches)", call.id)
        else ToolOutput.clamp(matches.joinToString("\n"), call.id)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ToolOutput.error(call.id, e.message ?: "glob failed")
    }

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            when (val c = glob[i]) {
                '*' -> {
                    val doubleStar = i + 1 < glob.length && glob[i + 1] == '*'
                    if (doubleStar) {
                        sb.append(".*"); i += 2
                        if (i < glob.length && glob[i] == '/') i++
                        continue
                    } else sb.append("[^/]*")
                }
                '?' -> sb.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append('$')
        return sb.toString().toRegex()
    }
}

class GrepTool(private val ws: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "grep",
        description = "Search file contents for a regex. Returns matching lines with path:line: prefixes.",
        category = ToolCategory.SEARCH,
        destructive = false,
        parameters = objectParams(
            "pattern" to stringProp("Regular expression to search for"),
            "path" to stringProp("Relative directory to search (default: workspace root)"),
            "glob" to stringProp("Optional filename glob to limit the search (e.g. *.kt)"),
            required = listOf("pattern")
        )
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = try {
        val pattern = call.arguments["pattern"] as? String ?: error("pattern is required")
        val base = (call.arguments["path"] as? String).orEmpty()
        val glob = call.arguments["glob"] as? String
        val regex = pattern.toRegex()
        val globRegex = glob?.let { filenameGlobRegex(it) }
        val results = StringBuilder()
        var shown = 0
        val limit = 200
        withContext(Dispatchers.IO) {
            for (entry in ws.walk(base)) {
                if (entry.isDir) continue
                if (globRegex != null && !globRegex.matches(entry.name)) continue
                val text = try { ws.read(entry.path) } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                } ?: continue
                text.lineSequence().forEachIndexed { idx, line ->
                    if (regex.containsMatchIn(line)) {
                        results.append(entry.path).append(':').append(idx + 1).append(": ")
                            .append(line).append('\n')
                        shown++
                    }
                }
                if (shown >= limit) break
            }
        }
        if (shown == 0) ToolOutput.clamp("(no matches)", call.id)
        else ToolOutput.clamp(results.toString().trimEnd(), call.id)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        ToolOutput.error(call.id, e.message ?: "grep failed")
    }

    private fun filenameGlobRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append('$')
        return sb.toString().toRegex()
    }
}
