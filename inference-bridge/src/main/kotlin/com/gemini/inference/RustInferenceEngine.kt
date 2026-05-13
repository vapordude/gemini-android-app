package com.gemini.inference

import android.content.Context
import com.gemini.domain.GenerateRequest
import com.gemini.domain.InferenceEngine
import com.gemini.domain.ModelHandle
import com.gemini.domain.RuntimeInfo
import com.gemini.domain.Token
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class RustInferenceEngine(private val appContext: Context) : InferenceEngine {

    private val modelsDir: File by lazy {
        File(appContext.filesDir, "models").apply { mkdirs() }
    }

    override suspend fun info(): RuntimeInfo = RuntimeInfo(
        version = NativeInference.version(),
        arch = systemArch(),
        isa = "scalar",
        threads = 1,
        modelLoaded = null,
    )

    override suspend fun listModels(): List<ModelHandle> =
        modelsDir.listFiles().orEmpty().filter { it.isFile }.map {
            ModelHandle(id = it.name, path = it.absolutePath, archTag = "unknown")
        }

    override suspend fun loadModel(path: String): Result<ModelHandle> = runCatching {
        val archTag = NativeInference.loadModel(path)
        ModelHandle(id = File(path).name, path = path, archTag = archTag)
    }

    override fun generate(request: GenerateRequest): Flow<Token> = flow {
        emit(Token(text = "", done = true))
    }

    private fun systemArch(): String = when (val a = System.getProperty("os.arch")) {
        "aarch64" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> a ?: "unknown"
    }
}
