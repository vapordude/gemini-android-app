package nz.kaimahi.bridge.screens

import nz.kaimahi.domain.screens.ButtonStyle
import nz.kaimahi.domain.screens.HeadingLevel
import nz.kaimahi.domain.screens.ScreenIcon
import nz.kaimahi.domain.screens.ScreenSpec
import nz.kaimahi.domain.screens.StripeTone
import nz.kaimahi.domain.screens.WidgetSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format correctness for ScreenSpec ↔ JSON. The agent emits this
 * JSON through `create_screen`; if the round-trip drifts, the screen
 * silently degrades. Cover each widget kind plus an end-to-end Stack
 * round-trip.
 */
class ScreenSpecJsonTest {

    private fun roundTrip(spec: ScreenSpec): ScreenSpec? {
        val json = ScreenSpecJson.toJson(spec)
        return ScreenSpecJson.fromJson(JSONObject(json.toString()))
    }

    @Test
    fun stack_with_heading_body_round_trips() {
        val spec = ScreenSpec.Stack(
            id = "habits",
            title = "Daily habits",
            createdBy = "agent:test",
            icon = ScreenIcon.List,
            widgets = listOf(
                WidgetSpec.Heading(key = "h", text = "This week", level = HeadingLevel.H1),
                WidgetSpec.Body(key = "b", text = "Quick check-ins."),
                WidgetSpec.Divider(key = "d"),
            ),
        )
        val out = roundTrip(spec)
        assertNotNull(out)
        assertEquals("habits", out!!.id)
        assertEquals("Daily habits", out.title)
        assertEquals(ScreenIcon.List, out.icon)
        assertEquals("agent:test", out.createdBy)
        val stack = out as ScreenSpec.Stack
        assertEquals(3, stack.widgets.size)
        assertEquals(HeadingLevel.H1, (stack.widgets[0] as WidgetSpec.Heading).level)
    }

    @Test
    fun checklist_widget_preserves_data_key_and_tools() {
        val spec = ScreenSpec.Stack(
            id = "x", title = "X",
            widgets = listOf(
                WidgetSpec.Checklist(
                    key = "items",
                    dataKey = "tasks",
                    emptyHint = "Nothing yet",
                    onAddTool = "add_task",
                )
            ),
        )
        val out = roundTrip(spec) as ScreenSpec.Stack
        val w = out.widgets[0] as WidgetSpec.Checklist
        assertEquals("tasks", w.dataKey)
        assertEquals("Nothing yet", w.emptyHint)
        assertEquals("add_task", w.onAddTool)
    }

    @Test
    fun item_list_widget_preserves_tap_tool() {
        val spec = ScreenSpec.Stack(
            id = "x", title = "X",
            widgets = listOf(
                WidgetSpec.ItemList(
                    key = "feed",
                    dataKey = "stories",
                    onItemTapTool = "open_story",
                )
            ),
        )
        val out = roundTrip(spec) as ScreenSpec.Stack
        val w = out.widgets[0] as WidgetSpec.ItemList
        assertEquals("stories", w.dataKey)
        assertEquals("open_story", w.onItemTapTool)
    }

    @Test
    fun info_card_preserves_stripe_and_optional_title() {
        val spec = ScreenSpec.Stack(
            id = "x", title = "X",
            widgets = listOf(
                WidgetSpec.InfoCard(
                    key = "note",
                    title = "Heads-up",
                    body = "Built today",
                    stripe = StripeTone.Koura,
                )
            ),
        )
        val out = roundTrip(spec) as ScreenSpec.Stack
        val w = out.widgets[0] as WidgetSpec.InfoCard
        assertEquals("Heads-up", w.title)
        assertEquals("Built today", w.body)
        assertEquals(StripeTone.Koura, w.stripe)
    }

    @Test
    fun info_card_with_null_title_round_trips() {
        val spec = ScreenSpec.Stack(
            id = "x", title = "X",
            widgets = listOf(WidgetSpec.InfoCard(key = "i", body = "no title here")),
        )
        val out = roundTrip(spec) as ScreenSpec.Stack
        val w = out.widgets[0] as WidgetSpec.InfoCard
        assertEquals(null, w.title)
        assertEquals(StripeTone.None, w.stripe)
    }

    @Test
    fun button_preserves_style_and_tool_args() {
        val spec = ScreenSpec.Stack(
            id = "x", title = "X",
            widgets = listOf(
                WidgetSpec.Button(
                    key = "act",
                    label = "Reset",
                    style = ButtonStyle.Destructive,
                    onTapTool = "reset_week",
                    onTapArgs = mapOf("hard" to true),
                )
            ),
        )
        val out = roundTrip(spec) as ScreenSpec.Stack
        val w = out.widgets[0] as WidgetSpec.Button
        assertEquals(ButtonStyle.Destructive, w.style)
        assertEquals("reset_week", w.onTapTool)
        assertEquals(true, w.onTapArgs["hard"])
    }

    @Test
    fun unknown_widget_type_is_dropped_not_crashed() {
        val raw = JSONObject(
            """
            {
              "id":"x","title":"X","kind":"stack",
              "widgets":[
                {"type":"divider","key":"d"},
                {"type":"future_widget","key":"f","prop":"value"},
                {"type":"body","key":"b","text":"after"}
              ]
            }
            """.trimIndent()
        )
        val spec = ScreenSpecJson.fromJson(raw) as ScreenSpec.Stack
        assertEquals(2, spec.widgets.size)
        assertTrue(spec.widgets[0] is WidgetSpec.Divider)
        assertEquals("after", (spec.widgets[1] as WidgetSpec.Body).text)
    }

    @Test
    fun missing_id_or_title_returns_null() {
        val noId = JSONObject().put("title", "X").put("widgets", org.json.JSONArray())
        val noTitle = JSONObject().put("id", "x").put("widgets", org.json.JSONArray())
        assertEquals(null, ScreenSpecJson.fromJson(noId))
        assertEquals(null, ScreenSpecJson.fromJson(noTitle))
    }
}
