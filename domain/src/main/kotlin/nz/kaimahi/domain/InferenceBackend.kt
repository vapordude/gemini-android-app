package nz.kaimahi.domain

/**
 * Kotlin-side mirror of `agent_core::InferenceBackend`. The agent loop
 * (in Rust or in a Kotlin port) calls `complete(prompt, stop)` for each
 * turn, then folds the result into the transcript.
 *
 * Errors carry the same structured shape as the Rust side
 * ([AgentError]) so they can be threaded back into the agent's next
 * prompt cycle (the "fail alarm" contract — see `docs/AGENTIC.md`).
 *
 * Implementations:
 * - `nz.kaimahi.bridge.CloudGeminiBackend` — wraps the existing
 *   `RestGeminiCore` for the cloud Gemini path.
 * - `nz.kaimahi.inference.LocalLmBackend` (TBD by the operator) — calls
 *   the native LM runtime through the JNI bridge.
 * - `MultiBackend` (this file) — composes the above with a policy.
 */
interface InferenceBackend {
    val name: String
    val available: Boolean
        get() = true

    /** Run the model until any of [stop] appears in the output. Returns
     *  the accumulated text excluding the stop sequence, or a typed
     *  failure that the agent loop can fold into the transcript. */
    suspend fun complete(prompt: String, stop: List<String> = emptyList()): BackendResult
}

sealed class BackendResult {
    data class Ok(val text: String) : BackendResult()
    data class Failed(val error: AgentError) : BackendResult()
}

/**
 * Composes multiple backends with a selection policy. Mirror of
 * `agent_core::multi::MultiBackend` on the Rust side. Both can be
 * present in a session and the agent picks per call.
 */
class MultiBackend(
    private val backends: List<InferenceBackend>,
    private val policy: Policy = Policy.PreferFirst,
) : InferenceBackend {

    override val name: String = "multi(${backends.joinToString(",") { it.name }})"

    private var cursor: Int = 0

    override suspend fun complete(prompt: String, stop: List<String>): BackendResult {
        val order = order()
        var lastErr: AgentError? = null
        for (idx in order) {
            val b = backends[idx]
            if (!b.available) continue
            when (val r = b.complete(prompt, stop)) {
                is BackendResult.Ok -> return r
                is BackendResult.Failed -> {
                    lastErr = AgentError(
                        kind = r.error.kind,
                        source = "${b.name}/${r.error.source}",
                        message = r.error.message,
                    )
                }
            }
        }
        return BackendResult.Failed(
            lastErr ?: AgentError(
                kind = ErrorKind.Inference,
                source = "multi",
                message = "no available backend",
            )
        )
    }

    private fun order(): List<Int> {
        val n = backends.size
        return when (policy) {
            Policy.PreferFirst -> (0 until n).toList()
            Policy.PreferLast -> (0 until n).reversed().toList()
            Policy.RoundRobin -> {
                val start = if (n == 0) 0 else cursor % n
                cursor = (cursor + 1) and 0x7FFFFFFF
                (0 until n).map { (start + it) % n }
            }
        }
    }

    enum class Policy { PreferFirst, PreferLast, RoundRobin }
}

/** Structured backend / tool / network / validation failure. Mirror of
 *  `agent_core::AgentError`. */
data class AgentError(
    val kind: ErrorKind,
    val source: String,
    val message: String,
) {
    fun toTranscriptBlock(): String = "\nERROR [${kind.tag}/$source]: $message\n"
}

enum class ErrorKind(val tag: String) {
    Inference("inference"),
    Tool("tool"),
    Network("network"),
    Validation("validation"),
}
