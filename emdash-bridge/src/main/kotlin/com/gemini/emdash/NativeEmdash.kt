package com.gemini.emdash

internal object NativeEmdash {
    private val loaded: Boolean = runCatching { System.loadLibrary("gemini_native") }.isSuccess

    @JvmStatic external fun nativeClientVersion(): String

    fun clientVersion(): String =
        if (loaded) runCatching { nativeClientVersion() }.getOrElse { "stub" } else "stub"
}
