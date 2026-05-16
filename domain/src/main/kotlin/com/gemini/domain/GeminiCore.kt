package com.gemini.domain

import kotlinx.coroutines.flow.Flow

interface GeminiCore {
    suspend fun init(config: Map<String, Any>): GeminiResult
    suspend fun setProjectFolder(uri: String): GeminiResult
    suspend fun sendMessage(text: String): GeminiResult

    /**
     * Send a multimodal user turn with [attachments]. Default delegates to
     * the text-only path so drivers that don't support attachments stay
     * functional without overriding.
     */
    suspend fun sendMessage(text: String, attachments: List<Attachment>): GeminiResult =
        sendMessage(text)

    suspend fun resetSession(): GeminiResult
    suspend fun loadHistory(): List<GeminiMessage>

    /**
     * Override the system instruction for subsequent turns. Used by the
     * chat-room engine to give each seat its persona without prefixing
     * every user message. Default implementation is a no-op so drivers
     * that don't yet support runtime system-prompt changes keep working.
     */
    suspend fun setSystemPrompt(prompt: String?) { /* no-op default */ }

    /** Stream of in-flight events (tool calls, approvals, tool results). */
    val events: Flow<GeminiEvent>

    /** Resolve the UI decision for a pending tool call. */
    suspend fun resolveToolDecision(callId: String, decision: ToolDecision)

    /** Introspect the tools currently exposed to the model. */
    fun availableTools(): List<ToolSpec>
}

sealed class GeminiResult {
    data class Success(val response: String) : GeminiResult()
    data class Error(val message: String) : GeminiResult()
}

enum class MessageRole { USER, MODEL, TOOL, SYSTEM }

data class GeminiMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val role: MessageRole = if (isUser) MessageRole.USER else MessageRole.MODEL,
    val toolCall: ToolCall? = null,
    val toolResult: ToolCallResult? = null,
    // Local file paths for attachments rendered as thumbnails in the bubble.
    // Empty for messages without attachments.
    val attachmentPaths: List<String> = emptyList()
)
