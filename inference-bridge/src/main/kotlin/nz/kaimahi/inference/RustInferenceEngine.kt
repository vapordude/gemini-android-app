package nz.kaimahi.inference

import android.content.Context
import nz.kaimahi.domain.GenerateRequest
import nz.kaimahi.domain.InferenceEngine
import nz.kaimahi.domain.ModelHandle
import nz.kaimahi.domain.RuntimeInfo
import nz.kaimahi.domain.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext

/**
 * Hard upper bound on any single `nativeGenerate` call. If generation
 * is still running when this fires, [NativeInference.nativeRequestCancel]
 * gets called and the Rust decode loop breaks at its next token. On
 * any reasonable hardware + model combination, generation completes
 * well under this; the cap exists so a pathological state (model
 * stuck in a loop, runaway max-tokens budget, hung session) doesn't
 * burn the device's battery and CPU indefinitely.
 */
private const val GENERATE_HARD_TIMEOUT_MS: Long = 5L * 60 * 1000 // 5 minutes

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

    /**
     * Serialises state transitions on `handle` / `loadedPath` /
     * `loadedArch`. Held only across the fast JNI calls that swap
     * sessions — NOT held across `nativeGenerate`, which runs for
     * seconds. The Rust `SESSIONS` table already guards against
     * use-after-free when `nativeCloseSession` races with an
     * in-flight generate (the slot is taken out, subsequent
     * `with(handle, …)` lookups return None → INVALID_HANDLE), so
     * generate stays lock-free; this mutex only protects the swap
     * SEQUENCE on the Kotlin side from being observed half-applied
     * (handle = new but loadedPath = old, etc).
     */
    private val stateLock = Any()

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
        mmapPinned = isPinnedNow(),
    )

    /**
     * Reports whether the active session's mmap was `mlock`-ed. Cheap —
     * just hits the Rust side's `mmap_pinned` flag set at open time. No
     * lock needed: the handle field is atomic and stale reads can only
     * return `false` (a session that's been closed has its slot taken
     * out and `nativeIsPinned` returns `false` for an invalid handle).
     */
    fun isPinnedNow(): Boolean {
        if (!NativeInference.loaded) return false
        val h = handle.get()
        if (h == 0L) return false
        return runCatching { NativeInference.nativeIsPinned(h) }.getOrDefault(false)
    }

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
            // The native open + close calls are fast (millisecond-range
            // mmap + slot insert / drop), so it's fine to hold the
            // state lock across the entire swap sequence. Any concurrent
            // loadModel / close from another coroutine will queue.
            synchronized(stateLock) {
                // Close any previously loaded model first — Kotlin
                // holds at most one native session per engine instance.
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
            // Watchdog: at deadline, request cooperative cancel. The
            // Rust decode loop polls the cancel flag once per token
            // (K3) so it'll break at the next iteration, nativeGenerate
            // returns naturally, and we don't end up with a Java
            // thread blocked indefinitely on a JNI call we can't
            // forcibly interrupt.
            val watchdog = launch {
                try {
                    delay(GENERATE_HARD_TIMEOUT_MS)
                    if (NativeInference.loaded) {
                        NativeInference.nativeRequestCancel(h)
                    }
                } catch (_: CancellationException) {
                    // nativeGenerate finished in time; watchdog
                    // cancelled below. Normal path.
                }
            }
            val status = try {
                NativeInference.nativeGenerate(
                    h,
                    request.prompt,
                    callback,
                    maxOf(request.maxNewTokens, 1),
                    request.temperature.coerceAtLeast(0.0f),
                    request.topK.coerceAtLeast(1),
                    request.seed,
                )
            } finally {
                watchdog.cancel()
            }
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
        awaitClose {
            // Cooperative cancellation: tell the Rust decode loop to
            // stop at its next token. Without this, the Rust thread
            // keeps generating tokens that get silently dropped after
            // the flow collector goes away, holding the JNI thread and
            // wasting compute until the natural max-tokens limit.
            val h = handle.get()
            if (h != 0L && NativeInference.loaded) {
                NativeInference.nativeRequestCancel(h)
            }
            job.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /** Reset the per-session KV cache without unloading the model. */
    suspend fun resetSession() = withContext(Dispatchers.IO) {
        synchronized(stateLock) {
            val h = handle.get()
            if (h != 0L) NativeInference.nativeResetSession(h)
        }
    }

    /**
     * Release the current session. Safe to call repeatedly; safe to
     * call from `ViewModel.onCleared()` (non-suspend, runs on main
     * thread — the lock is held only for the duration of one fast
     * JNI call). After close, the engine instance can be discarded.
     */
    fun close() {
        synchronized(stateLock) {
            val h = handle.getAndSet(0L)
            if (h != 0L) NativeInference.nativeCloseSession(h)
            loadedPath = null
        }
    }

    private fun systemArch(): String = when (val a = System.getProperty("os.arch")) {
        "aarch64" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> a ?: "unknown"
    }
}
