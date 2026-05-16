package nz.kaimahi.domain

/**
 * A "persona" the user can chat with on the Roleplay screen. Persona maps to
 * a custom system prompt, default model preferences, and a fixed driver
 * preference (so a persona can pin itself to the local Gemma 4 driver or to
 * remote Gemini).
 */
data class Persona(
    val id: String,
    val name: String,
    val avatar: String,
    val systemPrompt: String,
    val suggestedModel: String? = null,
    val temperature: Float = 0.8f,
    val driverPreference: DriverPreference = DriverPreference.ANY,
)

enum class DriverPreference {
    /** No constraint — use whatever the user's global setting picks. */
    ANY,
    /** Pin to the local Rust driver. Falls back to ANY if local isn't ready. */
    LOCAL,
    /** Pin to the remote Gemini driver. */
    REMOTE,
}
