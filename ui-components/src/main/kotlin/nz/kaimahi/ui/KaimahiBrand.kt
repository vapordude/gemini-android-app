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
    const val VERSION: String = "v1.0.0"
    const val WHAKATAUKI_REO: String =
        "He aha te mea nui o te ao? He tāngata, he tāngata, he tāngata."
    const val WHAKATAUKI_EN: String =
        "What is the most important thing in the world? It is people."
}
