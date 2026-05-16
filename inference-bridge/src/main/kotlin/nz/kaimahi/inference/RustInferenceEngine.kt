package nz.kaimahi.inference

import android.content.Context
import nz.kaimahi.domain.GenerateRequest
import nz.kaimahi.domain.InferenceEngine
import nz.kaimahi.domain.ModelHandle
import nz.kaimahi.domain.RuntimeInfo
import nz.kaimahi.domain.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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
        val raw = withContext(Dispatchers.IO) {
            NativeInference.generate(request.prompt, request.maxNewTokens)
        }
        if (raw.startsWith("error:")) {
            throw IllegalStateException(raw.removePrefix("error:").trim())
        }
        // Emit the full response as a single token; streaming word-by-word
        // requires a nativeGenerateStream callback surface which is planned
        // but not yet in the JNI shim. One token keeps the UI responsive
        // enough for now and the chat bubble renders as soon as it arrives.
        emit(Token(text = raw, done = false))
        emit(Token(text = "", done = true))
    }

    private fun systemArch(): String = when (val a = System.getProperty("os.arch")) {
        "aarch64" -> "aarch64"
        "x86_64" -> "x86_64"
        else -> a ?: "unknown"
    }
}
