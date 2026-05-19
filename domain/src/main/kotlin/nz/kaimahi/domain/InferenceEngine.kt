package nz.kaimahi.domain

import kotlinx.coroutines.flow.Flow

data class ModelHandle(
    val id: String,
    val path: String,
    val archTag: String,
    val vocabSize: Int? = null,
    val contextLength: Int? = null,
    val quantScheme: String? = null,
)

data class RuntimeInfo(
    val version: String,
    val arch: String,
    val isa: String,
    val threads: Int,
    val modelLoaded: ModelHandle?,
    /**
     * True iff the loaded model's mmap was `mlock`-ed. On stock
     * unprivileged Android this is almost always `false` because
     * `RLIMIT_MEMLOCK` is 64 KiB — the chat UI uses this to render
     * `pinned` vs `reclaimable` honestly.
     */
    val mmapPinned: Boolean = false,
)

data class GenerateRequest(
    val prompt: String,
    val maxNewTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val seed: Long = 0L,
    val stop: List<String> = emptyList(),
)

data class Token(
    val text: String,
    val tokenId: Int? = null,
    val done: Boolean = false,
)

interface InferenceEngine {
    suspend fun info(): RuntimeInfo
    suspend fun listModels(): List<ModelHandle>
    suspend fun loadModel(path: String): Result<ModelHandle>
    fun generate(request: GenerateRequest): Flow<Token>
}
