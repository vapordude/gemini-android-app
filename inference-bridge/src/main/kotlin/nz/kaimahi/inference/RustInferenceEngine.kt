package nz.kaimahi.inference

import android.content.Context
import nz.kaimahi.domain.GenerateRequest
import nz.kaimahi.domain.InferenceEngine
import nz.kaimahi.domain.ModelHandle
import nz.kaimahi.domain.RuntimeInfo
import nz.kaimahi.domain.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext

/**
 * Local inference engine backed by `libkaimahi_native.so`. Each call to
 * [loadModel] opens a fresh session in native land; subsequent calls
 * close any previous session first so the GPU/CPU weights aren't held
 * twice. [generate] streams tokens through a Channel-backed flow — the
 * native callback runs on whatever thread the JNI call was made from,
 * which is the same dispatcher this façade uses (Dispatchers.IO).
 */
class RustInferenceEngine(private val appContext: Context) : InferenceEngine {

    private val handle = AtomicLong(0L)
    @Volatile private var loadedPath: String? = null
    @Volatile private var loadedArch: String = "unknown"

    private val modelsDir: File by lazy {
        File(appContext.filesDir, "models").apply { mkdirs() }
    }

    override suspend fun info(): RuntimeInfo = RuntimeInfo(
        version = NativeInference.version(),
        arch = systemArch(),
        isa = if (NativeInference.loaded) "native" else "stub",
        threads = 1,
        modelLoaded = loadedPath?.let { p ->
            ModelHandle(
                id = File(p).name,
                path = p,
                archTag = loadedArch,
            )
        },
    )

    override suspend fun listModels(): List<ModelHandle> =
        modelsDir.listFiles().orEmpty().filter { it.isFile }.map {
            ModelHandle(
                id = it.name,
                path = it.absolutePath,
                archTag = NativeInference.probe(it.absolutePath),
            )
        }

    override suspend fun loadModel(path: String): Result<ModelHandle> = withContext(Dispatchers.IO) {
        runCatching {
            // Close any previously loaded model first — Kotlin holds at
            // most one native session per engine instance.
            val prior = handle.getAndSet(0L)
            if (prior != 0L) NativeInference.nativeCloseSession(prior)
            loadedPath = null

            if (!NativeInference.loaded) {
                error("Native runtime not available (libkaimahi_native.so missing). Build with `cargo ndk` first.")
            }
            if (!File(path).isFile) {
                error("Model file not found: $path")
            }
            val opened = NativeInference.nativeOpenSession(path)
            if (opened <= 0L) {
                val err = NativeInference.lastError().ifBlank { "open returned 0" }
                error("Could not open model: $err")
            }
            handle.set(opened)
            loadedPath = path
            loadedArch = NativeInference.probe(path)
            ModelHandle(id = File(path).name, path = path, archTag = loadedArch)
        }
    }

    override fun generate(request: GenerateRequest): Flow<Token> = callbackFlow {
        val h = handle.get()
        if (h == 0L) {
            trySend(Token(text = "(no model loaded — pick a GGUF file under Settings → Local model)", done = true))
            close()
            return@callbackFlow
        }
        val callback = NativeInference.TokenCallback { piece ->
            if (piece.isNotEmpty()) {
                trySend(Token(text = piece, done = false))
            }
        }
        // Run the native call on its own coroutine inside this flow's
        // scope so cancellation from the collector propagates.
        val job = launch(Dispatchers.IO) {
            val status = NativeInference.nativeGenerate(
                h,
                request.prompt,
                callback,
                maxOf(request.maxNewTokens, 1),
                request.temperature.coerceAtLeast(0.0f),
                request.topK.coerceAtLeast(1),
                request.seed,
            )
            val cause: Throwable? = if (status != NativeInference.Status.OK) {
                val tag = when (status) {
                    NativeInference.Status.INVALID_HANDLE -> "invalid handle"
                    NativeInference.Status.NO_TOKENIZER -> "tokenizer missing (vocab tag absent from GGUF)"
                    NativeInference.Status.EMPTY_PROMPT -> "empty prompt"
                    NativeInference.Status.INTERNAL -> "internal error"
                    else -> "status $status"
                }
                IllegalStateException(tag)
            } else null
            // Signal end-of-stream regardless of status.
            trySend(Token(text = "", done = true))
            close(cause)
        }
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    /** Reset the per-session KV cache without unloading the model. */
    suspend fun resetSession() = withContext(Dispatchers.IO) {
        val h = handle.get()
        if (h != 0L) NativeInference.nativeResetSession(h)
    }

    /** Release the current session. Safe to call repeatedly. */
    fun close() {
        val h = handle.getAndSet(0L)
        if (h != 0L) NativeInference.nativeCloseSession(h)
        loadedPath = null
    }

    private fun systemArch(): String = when (val a = System.getProperty("os.arch")) {
        "aarch64" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> a ?: "unknown"
    }
}
