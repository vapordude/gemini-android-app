package nz.kaimahi.bridge.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists dynamic-screen state under `<filesDir>/screens/<screen_id>/data.json`.
 * Used today for the canonical "daily todo" example — the local agent
 * (when wired) writes through the same shape for any screen it scaffolds.
 *
 * The on-disk schema is intentionally agent-friendly: pure JSON, no
 * proto / sealed classes. Anything the agent emits at the
 * `screen_data_put` tool can land here verbatim.
 */
class TodoStore(context: Context, screenId: String = "daily-todo") {

    private val dir: File = File(context.filesDir, "screens/$screenId").also { it.mkdirs() }
    private val file: File = File(dir, "data.json")

    data class Item(
        val id: String = UUID.randomUUID().toString(),
        val text: String,
        val meta: String,
        val done: Boolean = false,
        val overdue: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
    )

    data class Snapshot(
        val items: List<Item>,
        /** Wall-clock when the agent first scaffolded the screen (epoch ms). */
        val builtAt: Long?,
    )

    fun load(): Snapshot {
        val root = runCatching { JSONObject(file.readText()) }.getOrNull()
            ?: return Snapshot(items = emptyList(), builtAt = null)
        val arr = root.optJSONArray("items") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Item(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                text = o.optString("text"),
                meta = o.optString("meta"),
                done = o.optBoolean("done", false),
                overdue = o.optBoolean("overdue", false),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            )
        }
        val builtAt = root.optLong("builtAt", 0L).takeIf { it > 0 }
        return Snapshot(items = items, builtAt = builtAt)
    }

    fun save(snapshot: Snapshot) {
        val arr = JSONArray()
        snapshot.items.forEach { it ->
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("text", it.text)
                    .put("meta", it.meta)
                    .put("done", it.done)
                    .put("overdue", it.overdue)
                    .put("createdAt", it.createdAt),
            )
        }
        val root = JSONObject().put("items", arr)
        snapshot.builtAt?.let { root.put("builtAt", it) }
        runCatching { file.writeText(root.toString()) }
    }

    /** Convenience: ensure the screen has a `builtAt` stamp on first use. */
    fun ensureBuilt(): Long {
        val snap = load()
        if (snap.builtAt != null) return snap.builtAt
        val now = System.currentTimeMillis()
        save(snap.copy(builtAt = now))
        return now
    }

    fun add(text: String, meta: String = "added by Kaimahi") {
        val snap = load()
        val updated = snap.copy(items = snap.items + Item(text = text, meta = meta))
        save(updated)
    }

    fun toggleDone(id: String) {
        val snap = load()
        val updated = snap.copy(
            items = snap.items.map { if (it.id == id) it.copy(done = !it.done) else it },
        )
        save(updated)
    }

    fun delete(id: String) {
        val snap = load()
        save(snap.copy(items = snap.items.filterNot { it.id == id }))
    }

    fun clear() {
        runCatching { file.delete() }
    }
}
