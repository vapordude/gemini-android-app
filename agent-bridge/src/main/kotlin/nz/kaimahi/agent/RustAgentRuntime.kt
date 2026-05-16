package nz.kaimahi.agent

import nz.kaimahi.domain.AgentEvent
import nz.kaimahi.domain.AgentRunRequest
import nz.kaimahi.domain.AgentRuntime
import nz.kaimahi.domain.InferenceEngine
import nz.kaimahi.domain.ToolDecision
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class RustAgentRuntime(
    private val inference: InferenceEngine,
) : AgentRuntime {

    override fun run(request: AgentRunRequest): Flow<AgentEvent> = flow {
        emit(AgentEvent.Thinking("Agent runtime not yet wired to native loop."))
        emit(AgentEvent.Done)
    }

    override suspend fun resolveDecision(callId: String, decision: ToolDecision) {
        // TODO: forward to native dispatcher.
    }

    fun maxIterationsHint(): Int = NativeAgent.maxIterations()
}
