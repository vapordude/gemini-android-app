package com.gemini.localdriver

import com.gemini.domain.GeminiCore
import com.gemini.domain.GeminiEvent
import com.gemini.domain.GeminiMessage
import com.gemini.domain.GeminiResult
import com.gemini.domain.ToolDecision
import com.gemini.domain.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

/**
 * Local Gemma 4 E2B driver. Delegates to the Rust `libgemma4.so` shared
 * library via JNI. When the `.so` isn't packaged (e.g. on host CI builds
 * that skip the NDK cross-compile), this class lives in a "not ready"
 * state — `sendMessage` returns an error and the [DriverRouter] is
 * expected to skip it.
 */
class Gemma4LocalCore : GeminiCore {

    private val _events = MutableSharedFlow<GeminiEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<GeminiEvent> = _events.asSharedFlow()

    @Volatile private var handle: Long = 0L
    @Volatile private var ready: Boolean = libraryAvailable

    override suspend fun init(config: Map<String, Any>): GeminiResult {
        if (!libraryAvailable) {
            return GeminiResult.Error("Local Gemma 4 driver not packaged in this build")
        }
        val modelPath = config["model_path"] as? String
            ?: return GeminiResult.Error("model_path is required for the local driver")
        val configBytes = ByteArray(0)
        val h = initNative(modelPath, configBytes)
        if (h <= 0L) return GeminiResult.Error("Native init failed")
        handle = h
        ready = true
        return GeminiResult.Success("Local Gemma 4 ready")
    }

    override suspend fun setProjectFolder(uri: String): GeminiResult =
        GeminiResult.Success("Local driver ignores project folder")

    override suspend fun sendMessage(text: String): GeminiResult {
        if (!ready) return GeminiResult.Error("Local driver not ready")
        val status = sendMessageNative(handle, text.toByteArray(Charsets.UTF_8), TokenSink(_events))
        return if (status == 0) GeminiResult.Success("(stream complete)")
        else GeminiResult.Error("Local inference returned status $status")
    }

    override suspend fun resetSession(): GeminiResult {
        if (handle != 0L) resetNative(handle)
        return GeminiResult.Success("reset")
    }

    override suspend fun loadHistory(): List<GeminiMessage> = emptyList()

    override suspend fun resolveToolDecision(callId: String, decision: ToolDecision) {
        // Local driver doesn't yet expose tools — no-op.
    }

    override fun availableTools(): List<ToolSpec> = emptyList()

    fun close() {
        if (handle != 0L) {
            freeNative(handle)
            handle = 0L
            ready = false
        }
    }

    /**
     * Callback fed to the native side. Each emitted token piece becomes a
     * [GeminiEvent.MessageUpdated] / [GeminiEvent.MessageAdded] depending on
     * stream state. The native side calls `onToken(piece: ByteArray)` per
     * batch.
     */
    private class TokenSink(private val flow: MutableSharedFlow<GeminiEvent>) {
        @Suppress("unused") // called from JNI
        fun onToken(piece: ByteArray) {
            // Wire format: see rust/crates/gemma4-jni/src/wire.rs
            // Decoded chunk is appended to the live model message.
            // TODO once the native side actually emits tokens.
        }
    }

    private companion object {
        @JvmStatic private val libraryAvailable: Boolean = runCatching {
            System.loadLibrary("gemma4")
            true
        }.getOrElse { false }

        @JvmStatic external fun initNative(modelPath: String, config: ByteArray): Long
        @JvmStatic external fun sendMessageNative(handle: Long, msg: ByteArray, callback: Any): Int
        @JvmStatic external fun resetNative(handle: Long)
        @JvmStatic external fun freeNative(handle: Long)
    }
}
