package nz.kaimahi.agent

import android.content.Context
import nz.kaimahi.domain.InferenceBackend
import nz.kaimahi.domain.MultiBackend
import java.io.File

/**
 * Canonical wiring for the Kaimahi agent runtime. Returns the pieces an
 * operator drops into their composition root. Two backends in
 * `MultiBackend.PreferFirst` order: cloud first (if authenticated),
 * local second. Memory + training capture point at `filesDir` paths
 * the operator owns.
 *
 * Usage:
 * ```kotlin
 * val deps = KaimahiAgentDefaults.build(
 *     context,
 *     cloud = CloudGeminiBackend(geminiCore),
 *     local = LocalLmBackend(inferenceEngine),
 *     session = "current-chat",
 * )
 * // deps.backend, deps.session, deps.memoryDir, deps.trainingDir are
 * // ready to plug into your agent loop / JNI bridge.
 * ```
 *
 * Deliberately a data holder — no agent loop driver here. The Rust
 * `agent_core::Agent` is the loop; this is the bag of typed
 * dependencies the operator passes to it (via JNI when stitching the
 * runtime), or to a Kotlin port if they prefer.
 */
data class KaimahiAgentDeps(
    val backend: InferenceBackend,
    val session: String,
    val memoryDir: File,
    val trainingDir: File,
    val trainingCaptureEnabled: Boolean,
)

object KaimahiAgentDefaults {

    /** Default policy is PreferFirst (cloud → local) — cloud responses
     *  serve as the teacher signal when training capture is on; local
     *  takes over on cloud failure. Switch to PreferLast for offline-
     *  first, or RoundRobin to exercise both engines across a session. */
    fun build(
        context: Context,
        cloud: InferenceBackend? = null,
        local: InferenceBackend? = null,
        session: String = "default",
        policy: MultiBackend.Policy = MultiBackend.Policy.PreferFirst,
        trainingCaptureEnabled: Boolean = false,
    ): KaimahiAgentDeps {
        val backends = buildList {
            if (cloud != null) add(cloud)
            if (local != null) add(local)
        }
        require(backends.isNotEmpty()) {
            "KaimahiAgentDefaults.build requires at least one of cloud or local"
        }
        val combined: InferenceBackend = if (backends.size == 1) {
            backends.single()
        } else {
            MultiBackend(backends, policy)
        }
        return KaimahiAgentDeps(
            backend = combined,
            session = session,
            memoryDir = File(context.filesDir, "memory").apply { mkdirs() },
            trainingDir = File(context.filesDir, "training").apply { mkdirs() },
            trainingCaptureEnabled = trainingCaptureEnabled,
        )
    }
}
