package nz.kaimahi.domain

/**
 * Optional presentation hint the agent can attach to [AgentEvent]
 * variants. The renderer maps tone + emphasis to existing Kaimahi
 * tokens (ngahere / kowhai / kauri / coral / muted-text). Hints
 * **never** introduce new visual concepts — they pick among the
 * existing ones. The UI surface stays bounded; the agent gets
 * expressivity within it.
 *
 * Both fields default to neutral, so existing call sites don't need
 * to change.
 */
data class Hint(
    val tone: Tone = Tone.Default,
    val emphasis: Emphasis = Emphasis.Normal,
)

/** Semantic colouring the agent can request. Maps to existing
 *  [nz.kaimahi.ui.KaimahiExtendedColors] tokens at render time. */
enum class Tone {
    /** Neutral surface colour. */
    Default,
    /** Ngahere — completed successfully, healthy. */
    Success,
    /** Amber — heads-up, soft warning. */
    Warn,
    /** Pounamu — informational, the brand colour. */
    Info,
    /** Kowhai — "I learned / I remember". */
    Learning,
    /** Coral — something went wrong. */
    Danger,
}

/** How much weight the agent wants the rendering to carry. */
enum class Emphasis {
    /** Muted — minor narrative; safe to skim. */
    Subtle,
    /** Default — body weight. */
    Normal,
    /** Bold — the operator should notice this. */
    Strong,
}
