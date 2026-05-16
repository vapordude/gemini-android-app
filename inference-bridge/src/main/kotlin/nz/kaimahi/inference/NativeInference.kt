package nz.kaimahi.inference

/**
 * JNI surface for the native inference runtime. The library
 * `libkaimahi_native.so` is produced by `cargo-ndk` from `native/jni-shim`
 * and staged under `inference-bridge/src/main/jniLibs/<abi>/`.
 *
 * Three groups of methods:
 *
 *  - [nativeVersion] / [nativeProbe] — metadata, always safe to call.
 *  - [nativeOpenSession] / [nativeCloseSession] — session lifecycle. A
 *    successful open returns a positive handle.
 *  - [nativeGenerate] / [nativeResetSession] — per-session work. The
 *    callback object must expose `fun onToken(piece: String)`; the JNI
 *    side resolves it by signature.
 *
 * When the .so is missing (developer machine without Rust toolchain), the
 * façade falls back to stubbed returns: `nativeOpenSession` returns 0,
 * `nativeGenerate` returns a non-zero status. The Kotlin engine handles
 * both paths so the app still launches without native code.
 */
internal object NativeInference {
    @JvmField
    val loaded: Boolean = runCatching { System.loadLibrary("kaimahi_native") }.isSuccess

    // ---- metadata ----
    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeProbe(path: String): String
    @JvmStatic external fun nativeLastError(): String

    // ---- session ----
    @JvmStatic external fun nativeOpenSession(path: String): Long
    @JvmStatic external fun nativeCloseSession(handle: Long)
    @JvmStatic external fun nativeResetSession(handle: Long)

    // ---- generate ----
    @JvmStatic external fun nativeGenerate(
        handle: Long,
        prompt: String,
        callback: TokenCallback,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        seed: Long,
    ): Int

    // Backwards-compat: the older Kotlin façade looked up this name.
    // The native side maps it to nativeProbe.
    @JvmStatic external fun nativeLoadModel(path: String): String

    fun version(): String =
        if (loaded) runCatching { nativeVersion() }.getOrElse { "stub" } else "stub"

    fun probe(path: String): String =
        if (loaded) runCatching { nativeProbe(path) }.getOrElse { "unknown" } else "unknown"

    fun lastError(): String =
        if (loaded) runCatching { nativeLastError() }.getOrElse { "" } else ""

    fun loadModel(path: String): String =
        if (loaded) runCatching { nativeLoadModel(path) }.getOrElse { "unknown" } else "unknown"

    /** Receives each generated piece. Reflected by JNI via signature `(Ljava/lang/String;)V`. */
    fun interface TokenCallback {
        fun onToken(piece: String)
    }

    /** Status codes from `nativeGenerate`. Must mirror jni-shim/src/lib.rs. */
    object Status {
        const val OK = 0
        const val INVALID_HANDLE = 1
        const val NO_TOKENIZER = 2
        const val EMPTY_PROMPT = 3
        const val INTERNAL = 4
    }
}
