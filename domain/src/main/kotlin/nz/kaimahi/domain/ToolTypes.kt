package nz.kaimahi.domain

/**
 * Declarative description of a tool the model is allowed to call. The schema
 * is a JSON-Schema-like map that will be serialized into Gemini's function
 * declaration payload.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val category: ToolCategory,
    val destructive: Boolean,
    val parameters: Map<String, Any?>
)

enum class ToolCategory { FILES, SEARCH, SHELL, MEMORY, OTHER }

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: Map<String, Any?>
)

data class ToolCallResult(
    val callId: String,
    val ok: Boolean,
    val output: String,
    val truncated: Boolean = false,
    // Paths of image files produced by the tool (e.g. generate_image). The
    // chat bubble for the tool-result message renders them as thumbnails.
    val attachmentPaths: List<String> = emptyList()
)

/** User-level decision for a pending tool call surfaced by the model. */
sealed class ToolDecision {
    object Approve : ToolDecision()
    data class Reject(val reason: String) : ToolDecision()
    object AlwaysApprove : ToolDecision()
}

sealed class GeminiEvent {
    data class MessageAdded(val message: GeminiMessage) : GeminiEvent()
    data class MessageUpdated(val message: GeminiMessage) : GeminiEvent()
    data class ToolCallPending(val call: ToolCall) : GeminiEvent()
    data class ToolCallCompleted(val result: ToolCallResult) : GeminiEvent()
    /** High-level status for the UI — null label means clear. */
    data class Thinking(val label: String?) : GeminiEvent()
    data class Notice(val message: String) : GeminiEvent()
    /**
     * Token accounting for the current session, reported by Gemini via
     * `usageMetadata.totalTokenCount` on each streamed response. `limit` is
     * the model's `inputTokenLimit` when known. The UI uses the ratio to
     * render a context-fill indicator and to decide whether to show the
     * auto-compression banner.
     */
    data class TokenUsage(val total: Int, val limit: Int?) : GeminiEvent()
    /**
     * Typed progress marker — "reading x file", "grepping", "editing
     * config.yaml", "running shell command". Upserted by id: the same
     * marker arrives first with [AgentMarkerStatus.Running] (no detail
     * yet) and then again with status Done/Failed and the detail filled
     * in. The UI shows a one-line summary and expands to detail on tap.
     * Replaces the single-string `Thinking` label as the canonical
     * agent-activity surface; `Thinking` stays for backward compat.
     */
    data class MarkerUpserted(val marker: AgentMarker) : GeminiEvent()
    /** Clear a single marker by id (e.g. once it scrolls off relevance). */
    data class MarkerCleared(val id: String) : GeminiEvent()
}

/**
 * A typed status marker the agent emits while it works. Surfaces in the
 * chat UI as a row with a kind icon, a one-line label always visible,
 * and an optional expandable detail block. The status enum lets the row
 * render an in-progress spinner vs a done check vs a failure cross.
 */
data class AgentMarker(
    val id: String,
    val kind: AgentMarkerKind,
    /** Always-visible primary label, e.g. "Reading config.yaml". */
    val label: String,
    /**
     * Optional expandable detail — diff text, stdout, match list, the
     * full tool arguments JSON, etc. UI hides this until the row is
     * tapped to expand.
     */
    val detail: String? = null,
    val status: AgentMarkerStatus = AgentMarkerStatus.Running,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class AgentMarkerKind {
    /** Model is producing text output. */
    Responding,
    /** Internal reasoning, no tool call yet. */
    Thinking,
    /** read_file tool. */
    ReadingFile,
    /** write_file tool. */
    WritingFile,
    /** edit_file tool. */
    EditingFile,
    /** delete_file tool. */
    DeletingFile,
    /** list_dir tool. */
    ListingDir,
    /** glob tool. */
    Globbing,
    /** grep tool. */
    Grepping,
    /** run_shell_command tool. */
    ShellCommand,
    /** generate_image tool. */
    GeneratingImage,
    /** Any tool without a dedicated kind — uses the tool name as label. */
    Tool,
}

enum class AgentMarkerStatus {
    /** Work in progress — UI shows a spinner. */
    Running,
    /** Completed successfully — UI shows a check. */
    Done,
    /** Completed with an error — UI shows a cross + the error in detail. */
    Failed,
}
