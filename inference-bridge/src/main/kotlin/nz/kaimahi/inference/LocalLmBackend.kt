package nz.kaimahi.inference

import nz.kaimahi.domain.AgentError
import nz.kaimahi.domain.BackendResult
import nz.kaimahi.domain.ErrorKind
import nz.kaimahi.domain.GenerateRequest
import nz.kaimahi.domain.InferenceBackend
import nz.kaimahi.domain.InferenceEngine
import kotlinx.coroutines.flow.fold

/**
 * Adapts the on-device LM ([InferenceEngine] — wired by the operator
 * to the native runtime) into the agent-loop [InferenceBackend]
 * contract so it composes with [nz.kaimahi.bridge.CloudGeminiBackend]
 * under a single `MultiBackend`.
 *
 * Stop sequences are applied client-side here: we collect tokens until
 * the buffer ends with one of the requested stops, then truncate.
 * That's the smallest correct approach; engines that natively respect
 * stop sequences are free to short-circuit when wired in.
 *
 * Failures land in [BackendResult.Failed] with the structured
 * [AgentError] shape so the agent loop folds them into the next prompt
 * — the "fail alarms" contract from `docs/AGENTIC.md`.
 */
class LocalLmBackend(
    private val engine: InferenceEngine,
    override val name: String = "local-lm",
    private val maxNewTokens: Int = 512,
) : InferenceBackend {

    override val available: Boolean
        get() = runCatching { engine }.isSuccess

    override suspend fun complete(prompt: String, stop: List<String>): BackendResult {
        return try {
            val text = engine
                .generate(
                    GenerateRequest(
                        prompt = prompt,
                        maxNewTokens = maxNewTokens,
                        stop = stop,
                    )
                )
                .fold(StringBuilder()) { acc, token ->
                    acc.append(token.text)
                    val truncated = acc.applyStops(stop)
                    if (truncated != null) {
                        return@fold StringBuilder(truncated)
                    }
                    if (token.done) acc else acc
                }
                .toString()
            BackendResult.Ok(text)
        } catch (t: Throwable) {
            BackendResult.Failed(
                AgentError(
                    kind = classify(t),
                    source = "generate",
                    message = t.message ?: t::class.simpleName ?: "unknown",
                )
            )
        }
    }

    private fun StringBuilder.applyStops(stops: List<String>): String? {
        if (stops.isEmpty()) return null
        val current = this.toString()
        for (s in stops) {
            val idx = current.indexOf(s)
            if (idx >= 0) return current.substring(0, idx)
        }
        return null
    }

    private fun classify(t: Throwable): ErrorKind = when (t) {
        is java.io.IOException -> ErrorKind.Network
        is IllegalArgumentException, is IllegalStateException -> ErrorKind.Validation
        else -> ErrorKind.Inference
    }
}
