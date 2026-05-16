package com.gemini.bridge.persona

import android.content.Context
import com.gemini.domain.DriverPreference
import com.gemini.domain.Persona
import org.json.JSONObject
import java.io.File

/**
 * File-backed CRUD for [Persona] entries. Each persona is one JSON file under
 * app.filesDir/personas/<id>.json. Starter personas are seeded on first use
 * so the Roleplay screen has something to show before the user creates
 * anything custom.
 */
class PersonaStore(context: Context) {

    private val dir: File = File(context.filesDir, "personas").apply { mkdirs() }

    init {
        if ((dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).isEmpty()) {
            STARTER_PERSONAS.forEach(::save)
        }
    }

    fun list(): List<Persona> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .mapNotNull { f -> runCatching { decode(f.readText()) }.getOrNull() }
        .sortedBy { it.name }

    fun get(id: String): Persona? = File(dir, "$id.json").takeIf { it.exists() }
        ?.let { runCatching { decode(it.readText()) }.getOrNull() }

    fun save(persona: Persona) {
        File(dir, "${persona.id}.json").writeText(encode(persona))
    }

    fun delete(id: String): Boolean = File(dir, "$id.json").delete()

    private fun encode(p: Persona): String = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("avatar", p.avatar)
        put("systemPrompt", p.systemPrompt)
        put("suggestedModel", p.suggestedModel ?: JSONObject.NULL)
        put("temperature", p.temperature.toDouble())
        put("driverPreference", p.driverPreference.name)
    }.toString(2)

    private fun decode(json: String): Persona {
        val o = JSONObject(json)
        return Persona(
            id = o.getString("id"),
            name = o.getString("name"),
            avatar = o.optString("avatar", "🤖"),
            systemPrompt = o.getString("systemPrompt"),
            suggestedModel = o.optString("suggestedModel").takeIf { it.isNotBlank() && it != "null" },
            temperature = o.optDouble("temperature", 0.8).toFloat(),
            driverPreference = runCatching {
                DriverPreference.valueOf(o.optString("driverPreference", "ANY"))
            }.getOrDefault(DriverPreference.ANY),
        )
    }

    private companion object {
        val STARTER_PERSONAS = listOf(
            Persona(
                id = "assistant",
                name = "Assistant",
                avatar = "💼",
                systemPrompt = "You are a focused, concise assistant. Answer directly, " +
                    "ask clarifying questions only when essential, and prefer code over " +
                    "prose when the user is debugging.",
                suggestedModel = "gemini-2.5-flash",
                temperature = 0.4f,
            ),
            Persona(
                id = "code-reviewer",
                name = "Code Reviewer",
                avatar = "🔍",
                systemPrompt = "You review code changes the user pastes or describes. " +
                    "Identify correctness bugs, subtle race conditions, error-handling " +
                    "gaps, and style violations specific to the language. Always quote " +
                    "the line you're flagging. Don't praise unless asked.",
                suggestedModel = "gemini-2.5-pro",
                temperature = 0.3f,
            ),
            Persona(
                id = "dungeon-master",
                name = "Dungeon Master",
                avatar = "🎲",
                systemPrompt = "You run a tabletop roleplaying game as the DM. Narrate " +
                    "scenes vividly, voice NPCs distinctly, and respect dice outcomes the " +
                    "player declares. Track inventory and HP in your head. Never break " +
                    "character unless the player types `[meta]`.",
                temperature = 0.9f,
            ),
            Persona(
                id = "rubber-duck",
                name = "Rubber Duck",
                avatar = "🦆",
                systemPrompt = "You are a rubber duck. Ask clarifying questions, mirror " +
                    "what the user just said, and pointedly say `quack` when their logic " +
                    "is circular. Don't solve the problem for them — help them solve it.",
                temperature = 0.6f,
            ),
            Persona(
                id = "private-tutor",
                name = "Private Tutor",
                avatar = "📚",
                systemPrompt = "You are a patient tutor. When the user asks a question, " +
                    "first check what they already know, then build the answer in steps. " +
                    "End each turn with one question that checks understanding.",
                temperature = 0.5f,
            ),
        )
    }
}
