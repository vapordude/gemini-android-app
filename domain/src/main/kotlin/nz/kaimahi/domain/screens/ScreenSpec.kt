package nz.kaimahi.domain.screens

/**
 * A dynamic screen — built by the local agent (or a user) at runtime
 * and rendered by the Compose host. The agent emits a [ScreenSpec] as
 * JSON through the `create_screen` tool; data inside it lives at
 * `<filesDir>/screens/<id>/data.json` and is read/written through
 * `read_screen_data` / `write_screen_data` tools.
 *
 * Sealed because the renderer dispatches over kinds at the top level;
 * widgets compose freely below.
 *
 * Stays in `:domain` so neither the core-bridge persistence side nor
 * the app/ui renderer takes a dependency on the other.
 */
sealed class ScreenSpec {
    abstract val id: String
    abstract val title: String
    abstract val createdAt: Long
    abstract val createdBy: String
    abstract val icon: ScreenIcon

    /** A vertical stack of widgets — the default kind. Almost all
     *  agent-built screens land here. */
    data class Stack(
        override val id: String,
        override val title: String,
        override val createdAt: Long = System.currentTimeMillis(),
        override val createdBy: String = "user",
        override val icon: ScreenIcon = ScreenIcon.Default,
        val widgets: List<WidgetSpec>,
    ) : ScreenSpec()
}

/** Icon hint for the drawer entry. The renderer maps these to
 *  Material 3 icons; the design system locks the visual. */
enum class ScreenIcon { Default, List, Note, News, Calendar, Inbox, Heart, Bolt }

/** Widgets are the building blocks the agent composes. Tight set in
 *  v1 — easy for a 2B-class model to emit correctly and easy for the
 *  renderer to type-dispatch over. Add primitives only when a real
 *  use case demands them. */
sealed class WidgetSpec {
    /** Unique within the screen — also the data key when the widget
     *  binds to screen data. */
    abstract val key: String

    /** Plain heading text. */
    data class Heading(
        override val key: String,
        val text: String,
        val level: HeadingLevel = HeadingLevel.H2,
    ) : WidgetSpec()

    /** Body paragraph. Wraps; reads as prose. */
    data class Body(
        override val key: String,
        val text: String,
    ) : WidgetSpec()

    /** A non-interactive informational card with optional left stripe. */
    data class InfoCard(
        override val key: String,
        val title: String? = null,
        val body: String,
        val stripe: StripeTone = StripeTone.None,
    ) : WidgetSpec()

    /** A list of items bound to a data array at `dataKey`. Read-only.
     *
     *  Data shape expected:
     *  ```json
     *  [{ "label": "string", "meta": "optional string" }, ...]
     *  ```
     *  Other fields in each item are passed through to onItemTapTool. */
    data class ItemList(
        override val key: String,
        val dataKey: String,
        val emptyHint: String? = null,
        val onItemTapTool: String? = null,
    ) : WidgetSpec()

    /** A checklist bound to a data array. Same `label` / `meta` fields
     *  as ItemList, plus `done: bool` per item. Tapping toggles `done`
     *  on the underlying data through the screen's data store —
     *  no tool call needed for the toggle (the renderer mutates state
     *  directly because it's reversible and bounded). */
    data class Checklist(
        override val key: String,
        val dataKey: String,
        val emptyHint: String? = null,
        /** Optional tool to invoke when the user taps `+ Add`. The tool
         *  is expected to write a new entry to the screen's data. */
        val onAddTool: String? = null,
    ) : WidgetSpec()

    /** A standalone action button. Invokes `onTapTool` with `args`. */
    data class Button(
        override val key: String,
        val label: String,
        val style: ButtonStyle = ButtonStyle.Primary,
        val onTapTool: String? = null,
        val onTapArgs: Map<String, Any?> = emptyMap(),
    ) : WidgetSpec()

    /** Horizontal rule between sections. */
    data class Divider(
        override val key: String,
    ) : WidgetSpec()
}

enum class HeadingLevel { H1, H2, H3 }
enum class ButtonStyle { Primary, Secondary, Destructive }
enum class StripeTone { None, Whero, Koura, Ember, Muted }

/** A diff against an existing screen — what update_screen accepts. */
data class ScreenPatch(
    /** When non-null, replace the title. */
    val title: String? = null,
    /** When non-null, replace the icon. */
    val icon: ScreenIcon? = null,
    /** When non-null, replace the widget list wholesale. Per-widget
     *  patching is a follow-up; for v1, a full-widget-list replace is
     *  what `update_screen` does. */
    val widgets: List<WidgetSpec>? = null,
)
