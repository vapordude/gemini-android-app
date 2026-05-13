package nz.kaimahi.domain

import kotlinx.coroutines.flow.Flow

data class AgentRunRequest(
    val goal: String,
    val attachedDocs: List<String> = emptyList(),
    val maxIterations: Int = 50,
    val emdashProfile: String? = null,
)

sealed class AgentEvent {
    data class Thinking(val text: String) : AgentEvent()
    data class ToolCallPending(val callId: String, val name: String, val argsJson: String) : AgentEvent()
    data class ToolCallCompleted(val callId: String, val ok: Boolean, val outputLen: Int) : AgentEvent()
    data class Message(val text: String) : AgentEvent()
    object Done : AgentEvent()

    /** Structured failure. Mirrors `agent_core::AgentEvent::Error`.
     *  See `InferenceBackend.kt` for [AgentError] and `docs/AGENTIC.md`
     *  for the "failures flow back to the agent" contract. */
    data class Error(val error: AgentError) : AgentEvent()
}

interface AgentRuntime {
    fun run(request: AgentRunRequest): Flow<AgentEvent>
    suspend fun resolveDecision(callId: String, decision: ToolDecision)
}
