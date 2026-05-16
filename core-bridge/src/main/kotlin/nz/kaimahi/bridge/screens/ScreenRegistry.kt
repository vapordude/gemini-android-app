package nz.kaimahi.bridge.screens

import android.content.Context
import nz.kaimahi.domain.screens.ScreenSpec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence + lookup for dynamic screens. Each screen lives in its
 * own folder under `<filesDir>/screens/<slug>/`:
 *
 *   <id>/spec.json   — the [ScreenSpec] as JSON
 *   <id>/data.json   — opaque per-screen data the agent writes via
 *                      the `write_screen_data` tool, read by widgets
 *                      that bind to a `dataKey`
 *
 * Slug rule is shared with [nz.kaimahi.bridge.storage.ChatStore] —
 * lowercase, dash-separated, max 64 chars. The agent is responsible
 * for picking a clean id; if it picks a messy one we slug-it for it.
 *
 * Lookup is a directory listing; persistence is per-screen file
 * writes, no global index. Cheap to delete a screen (drop its folder)
 * and cheap to roll back if a write fails (only that screen's files
 * are touched).
 */
class ScreenRegistry(context: Context) {

    private val root: File = File(context.filesDir, "screens").also { it.mkdirs() }

    /** All persisted screens, newest-first by spec.json mtime. */
    fun list(): List<ScreenSpec> = root.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir ->
            val specFile = File(dir, "spec.json")
            if (!specFile.exists()) return@mapNotNull null
            runCatching {
                val obj = JSONObject(specFile.readText())
                ScreenSpecJson.fromJson(obj)
            }.getOrNull()
        }
        ?.sortedByDescending {
            File(root, "${slug(it.id)}/spec.json").lastModified()
        }
        ?: emptyList()

    fun get(id: String): ScreenSpec? {
        val specFile = File(root, "${slug(id)}/spec.json")
        if (!specFile.exists()) return null
        return runCatching { ScreenSpecJson.fromJson(JSONObject(specFile.readText())) }
            .getOrNull()
    }

    /** Create-or-replace a screen's spec.json. Returns the slug used. */
    fun save(spec: ScreenSpec): String {
        val safe = slug(spec.id)
        val dir = File(root, safe).also { it.mkdirs() }
        File(dir, "spec.json").writeText(ScreenSpecJson.toJson(spec).toString())
        return safe
    }

    /** Drops the entire `<id>/` folder including data. */
    fun delete(id: String): Boolean {
        val dir = File(root, slug(id))
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    /** Data store for a single screen. */
    fun dataStore(screenId: String): ScreenDataStore =
        ScreenDataStore(File(root, "${slug(screenId)}/data.json"))

    companion object {
        fun slug(raw: String): String =
            raw.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").take(64)
    }
}

/**
 * Opaque per-screen data. Stored as a single JSON object. Widgets
 * read by `dataKey`; tools write by `dataKey`. The shape is whatever
 * the agent emits — no schema enforcement.
 *
 * Mutation API uses [transform] to keep "load → mutate → save" atomic
 * (single read, single write).
 */
class ScreenDataStore internal constructor(private val file: File) {

    fun load(): JSONObject =
        runCatching { JSONObject(file.readText()) }.getOrNull() ?: JSONObject()

    fun save(obj: JSONObject) {
        file.parentFile?.mkdirs()
        runCatching { file.writeText(obj.toString()) }
    }

    fun transform(block: (JSONObject) -> JSONObject) {
        save(block(load()))
    }

    fun put(key: String, value: Any?) {
        transform { obj -> obj.put(key, value ?: JSONObject.NULL) }
    }

    /** Convenience: read a JSONArray field; returns empty array if missing. */
    fun array(key: String): JSONArray = load().optJSONArray(key) ?: JSONArray()
}
