package com.gemini.domain

/**
 * Local-only structured trace event. Non-extractive: all fields are typed
 * enums or non-PII scalars — never raw prompts or tool payloads. The trace
 * viewer screen tails these from `filesDir/traces/*.jsonl`.
 */
sealed class TraceEvent {
    abstract val timestampMs: Long

    data class ModelLoaded(
        override val timestampMs: Long,
        val archTag: String,
        val isa: String,
        val threads: Int,
    ) : TraceEvent()

    data class GenerateFinished(
        override val timestampMs: Long,
        val tokens: Int,
        val durationMs: Long,
        val tokensPerSec: Double,
    ) : TraceEvent()

    data class AgentIteration(
        override val timestampMs: Long,
        val iter: Int,
    ) : TraceEvent()

    data class ToolCall(
        override val timestampMs: Long,
        val name: String,
        val ok: Boolean,
        val durationMs: Long,
    ) : TraceEvent()

    data class Error(
        override val timestampMs: Long,
        val kind: String,
        val message: String,
    ) : TraceEvent()
}
