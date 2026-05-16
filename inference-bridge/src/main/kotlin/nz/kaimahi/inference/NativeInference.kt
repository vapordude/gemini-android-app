package nz.kaimahi.inference

/**
 * JNI surface for the native inference runtime. The library
 * `libkaimahi_native.so` is produced by `cargo-ndk` from `native/jni-shim`
 * and staged under `inference-bridge/src/main/jniLibs/<abi>/`.
 *
 * If the .so is missing (developer machine without Rust toolchain), the
 * bridge falls back to stub returns so the project still imports and the
 * app still launches.
 */
internal object NativeInference {
    private val loaded: Boolean = runCatching { System.loadLibrary("kaimahi_native") }.isSuccess

    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeLoadModel(path: String): String
    @JvmStatic external fun nativeGenerate(prompt: String, maxNewTokens: Int): String

    fun version(): String = if (loaded) runCatching { nativeVersion() }.getOrElse { "stub" } else "stub"
    fun loadModel(path: String): String =
        if (loaded) runCatching { nativeLoadModel(path) }.getOrElse { "unknown" } else "unknown"
    fun generate(prompt: String, maxNewTokens: Int): String =
        if (loaded) runCatching { nativeGenerate(prompt, maxNewTokens) }.getOrElse { "error: ${it.message}" }
        else "error: native inference library not available in this build"
}
