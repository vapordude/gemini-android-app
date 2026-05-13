package nz.kaimahi.domain

import kotlinx.coroutines.flow.Flow

data class AgentRunRequest(
    val goal: String,
    val attachedDocs: List<String> = emptyList(),
    val maxIterations: Int = 50,
    val emdashProfile: String? = null,
)

sealed class AgentEvent {
    /** Optional presentation hint. The agent can request tone +
     *  emphasis from the constrained palette; the renderer honours
     *  hints by picking among existing tokens — no new visuals,
     *  just expressivity within the typed surface. */
    abstract val hint: Hint

    data class Thinking(
        val text: String,
        override val hint: Hint = Hint(emphasis = Emphasis.Subtle),
    ) : AgentEvent()

    data class ToolCallPending(
        val callId: String,
        val name: String,
        val argsJson: String,
        override val hint: Hint = Hint(),
    ) : AgentEvent()

    data class ToolCallCompleted(
        val callId: String,
        val ok: Boolean,
        val outputLen: Int,
        override val hint: Hint = Hint(),
    ) : AgentEvent()

    data class Message(
        val text: String,
        override val hint: Hint = Hint(),
    ) : AgentEvent()

    object Done : AgentEvent() {
        override val hint: Hint = Hint()
    }

    /** Structured failure. Mirrors `agent_core::AgentEvent::Error`.
     *  See `InferenceBackend.kt` for [AgentError] and `docs/AGENTIC.md`
     *  for the "failures flow back to the agent" contract. */
    data class Error(
        val error: AgentError,
        override val hint: Hint = Hint(tone = Tone.Danger),
    ) : AgentEvent()
}

interface AgentRuntime {
    fun run(request: AgentRunRequest): Flow<AgentEvent>
    suspend fun resolveDecision(callId: String, decision: ToolDecision)
}
