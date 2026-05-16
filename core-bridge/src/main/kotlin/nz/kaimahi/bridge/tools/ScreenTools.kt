package nz.kaimahi.bridge.tools

import nz.kaimahi.bridge.screens.ScreenRegistry
import nz.kaimahi.bridge.screens.ScreenSpecJson
import nz.kaimahi.domain.ToolCall
import nz.kaimahi.domain.ToolCallResult
import nz.kaimahi.domain.ToolCategory
import nz.kaimahi.domain.ToolSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools the local + cloud agent use to scaffold screens at runtime.
 * Six tools, kept small + obvious so a 2B Gemma can drive them.
 *
 *   create_screen        — emit a fresh ScreenSpec, become canonical
 *   update_screen        — replace widgets / title / icon of an existing screen
 *   delete_screen        — drop a screen + its data
 *   list_screens         — enumerate persisted screens (id + title)
 *   read_screen_data     — read the screen's data.json
 *   write_screen_data    — write a value at a top-level key in data.json
 *
 * The renderer reads ScreenRegistry + ScreenDataStore directly for live
 * updates; these tools are how the agent participates.
 *
 * Each `execute` uses a block body so early-return on validation
 * failure is legal — expression-bodied try/catch wouldn't allow it.
 */

class CreateScreenTool(private val registry: ScreenRegistry) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "create_screen",
        description = "Create a new dynamic screen the user can pin to the drawer. " +
            "Spec is a JSON object with id, title, optional icon, and a widgets array. " +
            "See docs/dynamic-screens.md for the widget catalogue.",
        category = ToolCategory.OTHER,
        destructive = true,
        parameters = objectParams(
            "spec" to mapOf("type" to "object", "description" to "Full ScreenSpec JSON object."),
            required = listOf("spec"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult {
        return try {
            val specObj = call.arguments["spec"] as? Map<*, *>
                ?: error("'spec' must be a JSON object")
            @Suppress("UNCHECKED_CAST")
            val obj = JSONObject(specObj as Map<String, *>)
            val parsed = ScreenSpecJson.fromJson(obj)
                ?: return ToolOutput.error(call.id, "couldn't parse spec — needs at least id, title, widgets")
            val slug = registry.save(parsed)
            ToolOutput.clamp("Created screen '${parsed.title}' as $slug.", call.id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ToolOutput.error(call.id, e.message ?: "create_screen failed")
        }
    }
}

class UpdateScreenTool(private val registry: ScreenRegistry) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "update_screen",
        description = "Replace an existing screen's spec wholesale. Pass the same id " +
            "and the new full JSON.",
        category = ToolCategory.OTHER,
        destructive = true,
        parameters = objectParams(
            "spec" to mapOf("type" to "object", "description" to "Full replacement ScreenSpec."),
            required = listOf("spec"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult {
        return try {
            val specObj = call.arguments["spec"] as? Map<*, *>
                ?: error("'spec' must be a JSON object")
            @Suppress("UNCHECKED_CAST")
            val obj = JSONObject(specObj as Map<String, *>)
            val parsed = ScreenSpecJson.fromJson(obj)
                ?: return ToolOutput.error(call.id, "couldn't parse spec")
            if (registry.get(parsed.id) == null) {
                return ToolOutput.error(
                    call.id,
                    "no screen with id=${parsed.id}; use create_screen instead",
                )
            }
            registry.save(parsed)
            ToolOutput.clamp("Updated screen ${parsed.id}.", call.id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ToolOutput.error(call.id, e.message ?: "update_screen failed")
        }
    }
}

class DeleteScreenTool(private val registry: ScreenRegistry) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "delete_screen",
        description = "Delete a dynamic screen and all its data.",
        category = ToolCategory.OTHER,
        destructive = true,
        parameters = stringParam("id", "Screen id to delete."),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult {
        return try {
            val id = call.arguments["id"] as? String ?: error("id is required")
            val ok = registry.delete(id)
            if (ok) ToolOutput.clamp("Deleted screen $id.", call.id)
            else ToolOutput.error(call.id, "no screen with id=$id")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ToolOutput.error(call.id, e.message ?: "delete_screen failed")
        }
    }
}

class ListScreensTool(private val registry: ScreenRegistry) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "list_screens",
        description = "List all dynamic screens currently persisted. Returns " +
            "[{id, title, icon, createdBy, widgetCount}, ...].",
        category = ToolCategory.OTHER,
        destructive = false,
        parameters = objectParams(required = emptyList()),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult {
        return try {
            val arr = JSONArray()
            for (s in registry.list()) {
                val widgetCount = when (s) {
                    is nz.kaimahi.domain.screens.ScreenSpec.Stack -> s.widgets.size
                }
                arr.put(
                    JSONObject()
                        .put("id", s.id)
                        .put("title", s.title)
                        .put("icon", s.icon.name.lowercase())
                        .put("createdBy", s.createdBy)
                        .put("widgetCount", widgetCount)
                )
            }
            ToolOutput.clamp(arr.toString(2), call.id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ToolOutput.error(call.id, e.message ?: "list_screens failed")
        }
    }
}

class ReadScreenDataTool(private val registry: ScreenRegistry) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "read_screen_data",
        description = "Read the data.json for a screen. Returns the full JSON object.",
        category = ToolCategory.OTHER,
        destructive = false,
        parameters = stringParam("screen_id", "Screen id."),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult {
        return try {
            val id = call.arguments["screen_id"] as? String ?: error("screen_id is required")
            if (registry.get(id) == null) return ToolOutput.error(call.id, "no screen with id=$id")
            val obj = registry.dataStore(id).load()
            ToolOutput.clamp(obj.toString(2), call.id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ToolOutput.error(call.id, e.message ?: "read_screen_data failed")
        }
    }
}

class WriteScreenDataTool(private val registry: ScreenRegistry) : Tool {
    override val spec: ToolSpec = ToolSpec(
        name = "write_screen_data",
        description = "Write a value at a top-level key in a screen's data.json. " +
            "Pass `value` as a JSON value — string, number, boolean, array, or object.",
        category = ToolCategory.OTHER,
        destructive = true,
        parameters = objectParams(
            "screen_id" to stringProp("Screen id."),
            "key" to stringProp("Top-level data key to write."),
            "value" to mapOf("description" to "The value to store at `key`."),
            required = listOf("screen_id", "key", "value"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult {
        return try {
            val id = call.arguments["screen_id"] as? String ?: error("screen_id is required")
            val key = call.arguments["key"] as? String ?: error("key is required")
            val value = call.arguments["value"]
            if (registry.get(id) == null) return ToolOutput.error(call.id, "no screen with id=$id")
            val store = registry.dataStore(id)
            store.put(key, normaliseForJson(value))
            ToolOutput.clamp("wrote $id.$key", call.id)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ToolOutput.error(call.id, e.message ?: "write_screen_data failed")
        }
    }
}

/** Map → JSONObject / List → JSONArray, recursively. */
private fun normaliseForJson(v: Any?): Any? = when (v) {
    is Map<*, *> -> {
        val obj = JSONObject()
        for ((k, vv) in v) obj.put(k.toString(), normaliseForJson(vv))
        obj
    }
    is List<*> -> {
        val arr = JSONArray()
        for (e in v) arr.put(normaliseForJson(e))
        arr
    }
    else -> v
}
