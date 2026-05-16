package nz.kaimahi.ui

/**
 * Single source of truth for brand strings + version that appear in
 * drawer chrome, splash, About, and any future "this is what app
 * you're looking at" surface. Reference these from anywhere in the
 * Compose tree rather than hard-coding the literal — keeps drawer
 * footer / about-version / splash tagline in lock-step.
 */
object KaimahiBrand {
    const val NAME: String = "Kaimahi"
    const val TAGLINE: String = "Your local AI worker"
    const val FAMILY: String = "Cathedral AI"
    const val LOCALE_LINE: String = "Cathedral AI · Aotearoa"
    const val VERSION: String = "v0.3.0"
    const val WHAKATAUKI_REO: String =
        "He aha te mea nui o te ao? He tāngata, he tāngata, he tāngata."
    const val WHAKATAUKI_EN: String =
        "What is the most important thing in the world? It is people."

    /**
     * Canonical on-device runtime models. The Rust `model-runtime` crate
     * ports the Gemma 4 forward pass; these are the variants whose
     * architecture (RoPE schedule, sliding-window mask, KV-sharing) the
     * on-device path supports. Reference this list from the chat model
     * picker, onboarding, and About so they stay in lock-step with what
     * the runtime can actually load.
     */
    val ON_DEVICE_MODELS: List<String> = listOf(
        "Gemma 4 E2B",
        "Gemma 4 E4B",
    )
}
