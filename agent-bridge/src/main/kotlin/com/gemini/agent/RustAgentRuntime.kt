package com.gemini.agent

import com.gemini.domain.AgentEvent
import com.gemini.domain.AgentRunRequest
import com.gemini.domain.AgentRuntime
import com.gemini.domain.InferenceEngine
import com.gemini.domain.ToolDecision
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
