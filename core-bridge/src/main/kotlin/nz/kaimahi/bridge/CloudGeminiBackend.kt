package nz.kaimahi.bridge

import nz.kaimahi.domain.AgentError
import nz.kaimahi.domain.BackendResult
import nz.kaimahi.domain.ErrorKind
import nz.kaimahi.domain.GeminiCore
import nz.kaimahi.domain.GeminiResult
import nz.kaimahi.domain.InferenceBackend

/**
 * Adapts the cloud-Gemini REST client ([GeminiCore]) into the
 * [InferenceBackend] contract so it can be composed with the
 * on-device LM under a single `MultiBackend`.
 *
 * The cloud path is normally a streaming chat surface emitting
 * [nz.kaimahi.domain.GeminiEvent]s; this adapter collapses one
 * turn down to a single completion string. Stop sequences map onto
 * the standard `stopSequences` field of the Gemini generation config
 * (TODO: thread through when stitching the runtime).
 *
 * Failures land in [BackendResult.Failed] with [ErrorKind.Network] for
 * transport problems and [ErrorKind.Inference] for everything else,
 * so the agent loop folds the failure into its next prompt — the
 * "fail alarms" contract from `docs/AGENTIC.md`.
 */
class CloudGeminiBackend(
    private val core: GeminiCore,
    override val name: String = "cloud-gemini",
) : InferenceBackend {

    override val available: Boolean = true

    override suspend fun complete(prompt: String, stop: List<String>): BackendResult {
        // TODO(operator): collect the streamed events into a single
        // completion string, applying the `stop` sequences. The current
        // RestGeminiCore.sendMessage returns a GeminiResult and emits
        // events out-of-band; the canonical wiring is to await the
        // matching `Done`-equivalent event before returning here.
        return when (val r = core.sendMessage(prompt)) {
            is GeminiResult.Success -> BackendResult.Ok(r.response)
            is GeminiResult.Error -> BackendResult.Failed(
                AgentError(
                    kind = classify(r.message),
                    source = "sendMessage",
                    message = r.message,
                )
            )
        }
    }

    private fun classify(message: String): ErrorKind {
        val lower = message.lowercase()
        return when {
            "timeout" in lower || "unreachable" in lower || "network" in lower ->
                ErrorKind.Network
            "rate" in lower || "quota" in lower -> ErrorKind.Inference
            "invalid" in lower || "parse" in lower -> ErrorKind.Validation
            else -> ErrorKind.Inference
        }
    }
}
