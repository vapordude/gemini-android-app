package nz.kaimahi.bridge.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import nz.kaimahi.domain.ToolCall
import nz.kaimahi.domain.ToolCallResult
import nz.kaimahi.domain.ToolSpec

/**
 * Drives the local Gemma agent through a multi-turn loop:
 *
 *   user msg ─▶ build prompt ─▶ stream tokens
 *                                    │
 *                                    ▼
 *                            parse [CALL]…[/CALL]
 *                              │              │
 *               (tool call) ◀──┘              └──▶ done (final answer)
 *                    │
 *                    ▼
 *           run tool through host (approval gated)
 *                    │
 *                    ▼
 *           append [RESULT…][/RESULT] to history
 *                    │
 *                    └──▶ next iteration
 *
 * Transport-agnostic — the caller provides:
 *  - a [TokenStreamer] that turns a prompt into a `Flow<String>` of
 *    raw model tokens (the local inference engine wraps this).
 *  - a [ToolRunner] that executes one tool call with approval gating
 *    (RestGeminiCore wraps its existing `runSingleCall` for this).
 *
 * Stateless across runs; one instance per agent turn. Logically lives
 * alongside RestGeminiCore — the cloud path is the function-calling
 * equivalent.
 */
class LocalAgentLoop(
    private val tools: List<ToolSpec>,
    private val streamer: TokenStreamer,
    private val runner: ToolRunner,
    private val workspaceLabel: String? = null,
    private val modelName: String? = null,
    private val maxIterations: Int = 8,
) {

    fun interface TokenStreamer {
        fun stream(prompt: String): Flow<String>
    }

    fun interface ToolRunner {
        suspend fun run(call: ToolCall): ToolCallResult
    }

    /** Callbacks the host wires for UI updates. */
    data class Sink(
        /** Streaming text deltas — append to the live assistant bubble. */
        val onAssistantText: (String) -> Unit = {},
        /** Tool call detected; tool hasn't run yet. */
        val onCallStart: (ToolCall) -> Unit = {},
        /** Tool finished; result emitted by the runner. */
        val onCallResult: (ToolCall, ToolCallResult) -> Unit = { _, _ -> },
        /** Loop hit the iteration ceiling. */
        val onIterationLimit: () -> Unit = {},
        /** Stream truncated mid-call — model emitted [CALL] but no [/CALL]. */
        val onTruncated: () -> Unit = {},
    )

    /** Returns the full assistant text the loop produced. */
    suspend fun run(userMessage: String, sink: Sink = Sink()): String {
        val systemPrompt = SystemPromptBuilder.build(tools, workspaceLabel, modelName)
        val history = StringBuilder()
        history.append(userMessage)

        var assistantSoFar = StringBuilder()
        var iteration = 0
        while (iteration < maxIterations) {
            iteration++
            val prompt = formatPrompt(systemPrompt, history.toString())
            val parser = MarkerToolStreamParser()
            var detectedCall: ToolCall? = null

            streamer.stream(prompt)
                .takeWhile { detectedCall == null }
                .collect { chunk ->
                    val events = parser.feed(chunk)
                    handleEvents(events, sink, assistantSoFar) { detectedCall = it }
                }

            // If we cancelled mid-stream the parser hasn't been flushed.
            val tail = parser.flush()
            handleEvents(tail, sink, assistantSoFar) { detectedCall = it }

            val call = detectedCall
            if (call == null) {
                // Stream ended without a call — final answer reached.
                return assistantSoFar.toString().trim()
            }

            sink.onCallStart(call)
            val result = runner.run(call)
            sink.onCallResult(call, result)

            // Stitch the call + result into the history so the next
            // iteration's prompt contains them. We deliberately keep
            // the assistant's pre-call prose visible to the model so
            // it can reason continuously.
            history.append("\n\nAssistant: ")
            history.append(assistantSoFar.toString())
            history.append(MarkerToolStreamParser.OPEN)
            history.append(call.name)
            history.append("(")
            history.append(argsJson(call))
            history.append(")")
            history.append(MarkerToolStreamParser.CLOSE)
            history.append("\n")
            history.append(MarkerToolStreamParser.formatResult(call.id, result.ok, result.output))
            history.append("\n")

            assistantSoFar = StringBuilder()
        }
        sink.onIterationLimit()
        return assistantSoFar.toString().trim()
    }

    private inline fun handleEvents(
        events: List<MarkerToolStreamParser.Event>,
        sink: Sink,
        soFar: StringBuilder,
        onCall: (ToolCall) -> Unit,
    ) {
        for (e in events) {
            when (e) {
                is MarkerToolStreamParser.Event.Text -> {
                    soFar.append(e.text)
                    sink.onAssistantText(e.text)
                }
                is MarkerToolStreamParser.Event.Call -> {
                    onCall(e.call)
                    return
                }
                MarkerToolStreamParser.Event.Truncated -> {
                    sink.onTruncated()
                }
            }
        }
    }

    private fun formatPrompt(system: String, history: String): String =
        // Gemma 4 chat template: `<start_of_turn>user ... <end_of_turn>`
        // pair around the user content, then an open `<start_of_turn>model`
        // turn for the assistant to continue. The on-device runtime is
        // Gemma-4-only, so hardcoding the template here is correct;
        // a follow-up will read `tokenizer.chat_template` from the GGUF
        // for portability. The previous `"User: ... Assistant: "` format
        // pushed every model off-distribution, masking weight differences
        // across GGUF swaps and collapsing output to a shared boilerplate
        // prior — which read as "same forward regardless of GGUF".
        buildString {
            append("<start_of_turn>user\n")
            append(system)
            append("\n\n")
            append(history)
            append("<end_of_turn>\n<start_of_turn>model\n")
        }

    private fun argsJson(call: ToolCall): String {
        if (call.arguments.isEmpty()) return "{}"
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in call.arguments) {
            if (!first) sb.append(",")
            first = false
            sb.append('"').append(escape(k)).append('"').append(':')
            when (v) {
                null -> sb.append("null")
                is Number, is Boolean -> sb.append(v)
                else -> sb.append('"').append(escape(v.toString())).append('"')
            }
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
