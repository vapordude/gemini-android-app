package nz.kaimahi.bridge.research

/**
 * High-yield search terms used by the feed fetcher for tagging,
 * ranking, and per-topic chip filtering. Grouped by axis so the agent
 * (and future user-editable overrides) can reason about *why* a term
 * matched.
 *
 * See `docs/research/daily-front-page.md` for the rationale + the
 * Cathedral / Scarlet / Crimson / Lux scope these terms cover.
 */
object FeedKeywords {

    /** Architecture / training — the Scarlet + forge-core territory. */
    val ARCHITECTURE: List<String> = listOf(
        "state space models",
        "mamba",
        "linear attention",
        "ternary networks",
        "bitmamba",
        "speculative decoding",
        "quantization aware training",
        "gguf",
        "kv cache compression",
        "mixture of experts",
        "edge inference",
        "onnx optimization",
        "simd kernels",
        "sub-quadratic attention",
        "hybrid ssm-transformer",
        "rotary position embedding",
        "lyapunov stability training",
        "loss landscape",
        "rank collapse",
    )

    /** Cognition / consciousness — the Lux + research territory. */
    val COGNITION: List<String> = listOf(
        "integrated information theory",
        "free energy principle",
        "active inference",
        "quantum cognition",
        "orchestrated objective reduction",
        "panpsychism computational",
        "morphic resonance",
        "emergence and consciousness",
        "quantum biology",
        "information ontology",
        "cosmic fine-tuning",
        "anthropic principle",
        "holographic principle cognition",
        "entropic gravity",
        "causal emergence",
        "predictive processing",
        "attractor dynamics cognition",
        "topological field theory mind",
        "quantum darwinism",
    )

    /** Fringe-but-rigorous. */
    val FRINGE: List<String> = listOf(
        "biosemiotics",
        "autopoiesis",
        "enactivism",
        "neurophenomenology",
    )

    val ALL: List<String> = ARCHITECTURE + COGNITION + FRINGE

    /** Returns a list of (term, matched-in-text) pairs for a given body. */
    fun matchesIn(text: String): List<String> {
        val haystack = text.lowercase()
        return ALL.filter { haystack.contains(it) }
    }
}
