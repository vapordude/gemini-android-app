package com.gemini.domain

interface GeminiCore {
    suspend fun init(config: Map<String, Any>): GeminiResult
    suspend fun setProjectFolder(uri: String): GeminiResult
    suspend fun sendMessage(text: String): GeminiResult
    suspend fun resetSession(): GeminiResult
    suspend fun loadHistory(): List<GeminiMessage>
}

sealed class GeminiResult {
    data class Success(val response: String) : GeminiResult()
    data class Error(val message: String) : GeminiResult()
}

data class GeminiMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long
)
