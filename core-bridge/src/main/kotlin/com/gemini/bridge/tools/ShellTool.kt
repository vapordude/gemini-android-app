package com.gemini.bridge.tools

import com.gemini.bridge.termux.TermuxBridge
import com.gemini.domain.ToolCall
import com.gemini.domain.ToolCallResult
import com.gemini.domain.ToolCategory
import com.gemini.domain.ToolSpec

class RunShellCommandTool(private val termux: TermuxBridge) : Tool {
    override val spec = ToolSpec(
        name = "run_shell_command",
        description = "Execute a shell command inside Termux. Requires Termux installed and the " +
            "RUN_COMMAND permission granted. Returns combined stdout and stderr.",
        category = ToolCategory.SHELL,
        destructive = true,
        parameters = objectParams(
            "command" to stringProp("Command line to execute (e.g. `ls -al`)"),
            "workdir" to stringProp("Optional absolute working directory inside Termux"),
            required = listOf("command")
        )
    )

    override suspend fun execute(call: ToolCall): ToolCallResult = runCatching {
        val command = call.arguments["command"] as? String ?: error("command is required")
        val workdir = call.arguments["workdir"] as? String
        val r = termux.run(command, workdir)
        val body = buildString {
            append("exit=").append(r.exitCode).append('\n')
            if (r.stdout.isNotBlank()) append("--- stdout ---\n").append(r.stdout).append('\n')
            if (r.stderr.isNotBlank()) append("--- stderr ---\n").append(r.stderr).append('\n')
        }.trimEnd()
        if (r.ok) ToolOutput.clamp(body, call.id)
        else ToolOutput.error(call.id, body.ifBlank { "shell command failed" })
    }.getOrElse { ToolOutput.error(call.id, it.message ?: "shell failed") }
}
