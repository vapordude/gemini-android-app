package nz.kaimahi.bridge.screens

import nz.kaimahi.domain.screens.ButtonStyle
import nz.kaimahi.domain.screens.HeadingLevel
import nz.kaimahi.domain.screens.ScreenIcon
import nz.kaimahi.domain.screens.ScreenSpec
import nz.kaimahi.domain.screens.StripeTone
import nz.kaimahi.domain.screens.WidgetSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Marshals [ScreenSpec] to and from JSON. The agent emits JSON that
 * looks like this through `create_screen`:
 *
 *   {
 *     "id": "daily-habits",
 *     "title": "Daily habits",
 *     "icon": "list",
 *     "widgets": [
 *       {"type": "heading", "key": "h1", "text": "This week", "level": "h2"},
 *       {"type": "checklist", "key": "tasks", "dataKey": "items"},
 *       {"type": "button", "key": "reset", "label": "Reset week", "style": "secondary"}
 *     ]
 *   }
 *
 * `createdAt` + `createdBy` default to "now" + "user" when not
 * provided, so the agent doesn't need to set them.
 */
object ScreenSpecJson {

    fun toJson(spec: ScreenSpec): JSONObject = when (spec) {
        is ScreenSpec.Stack -> JSONObject()
            .put("id", spec.id)
            .put("kind", "stack")
            .put("title", spec.title)
            .put("icon", spec.icon.toWire())
            .put("createdAt", spec.createdAt)
            .put("createdBy", spec.createdBy)
            .put("widgets", JSONArray().apply { spec.widgets.forEach { put(widgetToJson(it)) } })
    }

    fun fromJson(obj: JSONObject): ScreenSpec? {
        val id = obj.optString("id").ifBlank { return null }
        val title = obj.optString("title").ifBlank { return null }
        val icon = iconFromWire(obj.optString("icon"))
        val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
        val createdBy = obj.optString("createdBy", "user")
        // kind defaults to stack — only kind defined in v1.
        val arr = obj.optJSONArray("widgets") ?: JSONArray()
        val widgets = mutableListOf<WidgetSpec>()
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            widgetFromJson(w)?.let(widgets::add)
        }
        return ScreenSpec.Stack(
            id = id,
            title = title,
            icon = icon,
            createdAt = createdAt,
            createdBy = createdBy,
            widgets = widgets,
        )
    }

    private fun widgetToJson(w: WidgetSpec): JSONObject = when (w) {
        is WidgetSpec.Heading -> JSONObject()
            .put("type", "heading")
            .put("key", w.key)
            .put("text", w.text)
            .put("level", w.level.name.lowercase())
        is WidgetSpec.Body -> JSONObject()
            .put("type", "body")
            .put("key", w.key)
            .put("text", w.text)
        is WidgetSpec.InfoCard -> JSONObject()
            .put("type", "info_card")
            .put("key", w.key)
            .put("title", w.title ?: JSONObject.NULL)
            .put("body", w.body)
            .put("stripe", w.stripe.name.lowercase())
        is WidgetSpec.ItemList -> JSONObject()
            .put("type", "item_list")
            .put("key", w.key)
            .put("dataKey", w.dataKey)
            .put("emptyHint", w.emptyHint ?: JSONObject.NULL)
            .put("onItemTapTool", w.onItemTapTool ?: JSONObject.NULL)
        is WidgetSpec.Checklist -> JSONObject()
            .put("type", "checklist")
            .put("key", w.key)
            .put("dataKey", w.dataKey)
            .put("emptyHint", w.emptyHint ?: JSONObject.NULL)
            .put("onAddTool", w.onAddTool ?: JSONObject.NULL)
        is WidgetSpec.Button -> JSONObject()
            .put("type", "button")
            .put("key", w.key)
            .put("label", w.label)
            .put("style", w.style.name.lowercase())
            .put("onTapTool", w.onTapTool ?: JSONObject.NULL)
            .put("onTapArgs", JSONObject(w.onTapArgs as Map<*, *>))
        is WidgetSpec.Divider -> JSONObject()
            .put("type", "divider")
            .put("key", w.key)
    }

    private fun widgetFromJson(o: JSONObject): WidgetSpec? {
        val key = o.optString("key").ifBlank { return null }
        return when (o.optString("type")) {
            "heading" -> WidgetSpec.Heading(
                key = key,
                text = o.optString("text"),
                level = headingFromWire(o.optString("level")),
            )
            "body" -> WidgetSpec.Body(key = key, text = o.optString("text"))
            "info_card" -> WidgetSpec.InfoCard(
                key = key,
                title = o.optString("title").takeIf { it.isNotBlank() && !o.isNull("title") },
                body = o.optString("body"),
                stripe = stripeFromWire(o.optString("stripe")),
            )
            "item_list" -> WidgetSpec.ItemList(
                key = key,
                dataKey = o.optString("dataKey").ifBlank { return null },
                emptyHint = o.optString("emptyHint").takeIf { it.isNotBlank() && !o.isNull("emptyHint") },
                onItemTapTool = o.optString("onItemTapTool").takeIf { it.isNotBlank() && !o.isNull("onItemTapTool") },
            )
            "checklist" -> WidgetSpec.Checklist(
                key = key,
                dataKey = o.optString("dataKey").ifBlank { return null },
                emptyHint = o.optString("emptyHint").takeIf { it.isNotBlank() && !o.isNull("emptyHint") },
                onAddTool = o.optString("onAddTool").takeIf { it.isNotBlank() && !o.isNull("onAddTool") },
            )
            "button" -> WidgetSpec.Button(
                key = key,
                label = o.optString("label"),
                style = buttonStyleFromWire(o.optString("style")),
                onTapTool = o.optString("onTapTool").takeIf { it.isNotBlank() && !o.isNull("onTapTool") },
                onTapArgs = jsonObjectToMap(o.optJSONObject("onTapArgs")),
            )
            "divider" -> WidgetSpec.Divider(key = key)
            else -> null
        }
    }

    private fun jsonObjectToMap(obj: JSONObject?): Map<String, Any?> {
        if (obj == null) return emptyMap()
        val out = mutableMapOf<String, Any?>()
        for (k in obj.keys()) {
            out[k] = when (val v = obj.opt(k)) {
                JSONObject.NULL -> null
                else -> v
            }
        }
        return out
    }
}

/* ── wire-format helpers ────────────────────────────────────────── */

private fun ScreenSpec.kindWire(): String = when (this) {
    is ScreenSpec.Stack -> "stack"
}
private fun ScreenIcon.toWire(): String = name.lowercase()
private fun iconFromWire(s: String): ScreenIcon =
    ScreenIcon.values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: ScreenIcon.Default
private fun headingFromWire(s: String): HeadingLevel =
    HeadingLevel.values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: HeadingLevel.H2
private fun buttonStyleFromWire(s: String): ButtonStyle =
    ButtonStyle.values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: ButtonStyle.Primary
private fun stripeFromWire(s: String): StripeTone =
    StripeTone.values().firstOrNull { it.name.equals(s, ignoreCase = true) } ?: StripeTone.None
