package nz.kaimahi.bridge.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Streaming-parser correctness checks. Critical-path tests because the
 * parser is what stands between the local model's chaotic token stream
 * and the chat bubble — any leaked partial marker would show `[CALL` in
 * the UI, and any missed close would leave the agent loop spinning.
 */
class MarkerToolStreamParserTest {

    private fun parser() = MarkerToolStreamParser(
        newCallId = AtomicInteger(0).let { c -> { "test-${c.incrementAndGet()}" } }
    )

    private fun MarkerToolStreamParser.feedAll(vararg chunks: String): List<MarkerToolStreamParser.Event> {
        val out = mutableListOf<MarkerToolStreamParser.Event>()
        chunks.forEach { out.addAll(this.feed(it)) }
        out.addAll(this.flush())
        return out
    }

    private fun text(events: List<MarkerToolStreamParser.Event>): String =
        events.filterIsInstance<MarkerToolStreamParser.Event.Text>().joinToString("") { it.text }

    private fun calls(events: List<MarkerToolStreamParser.Event>) =
        events.filterIsInstance<MarkerToolStreamParser.Event.Call>()

    @Test
    fun plain_text_streams_through() {
        val events = parser().feedAll("hello there")
        assertEquals("hello there", text(events))
        assertTrue(calls(events).isEmpty())
    }

    @Test
    fun single_call_single_chunk() {
        val events = parser().feedAll(
            "Let me check.\n[CALL]read_file({\"path\":\"README.md\"})[/CALL]"
        )
        assertEquals("Let me check.\n", text(events))
        val cs = calls(events)
        assertEquals(1, cs.size)
        assertEquals("read_file", cs[0].call.name)
        assertEquals("README.md", cs[0].call.arguments["path"])
    }

    @Test
    fun marker_split_across_chunks() {
        // Worst case — the open marker arrives one byte per chunk.
        val events = parser().feedAll(
            "ok ", "[", "C", "A", "L", "L", "]", "ls(", "{", "}", ")", "[", "/", "C", "A", "L", "L", "]"
        )
        assertEquals("ok ", text(events))
        val cs = calls(events)
        assertEquals(1, cs.size)
        assertEquals("ls", cs[0].call.name)
    }

    @Test
    fun text_after_call_streams_through() {
        // Belt-and-braces — the model isn't supposed to write after a
        // call, but if it does we shouldn't drop the prose either.
        val events = parser().feedAll(
            "first.[CALL]a({})[/CALL]\nsecond."
        )
        assertEquals("first.\nsecond.", text(events))
        assertEquals(1, calls(events).size)
    }

    @Test
    fun safe_tail_held_until_decided() {
        // After feeding "abc[CAL", we must NOT have leaked "[CAL".
        val p = parser()
        val early = p.feed("abc[CAL")
        val visible = text(early)
        assertTrue("got: '$visible'", "[" !in visible)
        // Resolve to a non-call: "[CAL" wasn't a marker.
        val late = p.feed("ifornia") + p.flush()
        assertEquals("abc[CALifornia", text(early + late))
    }

    @Test
    fun call_with_no_args_parses() {
        val events = parser().feedAll("[CALL]ping()[/CALL]")
        val cs = calls(events)
        assertEquals(1, cs.size)
        assertEquals("ping", cs[0].call.name)
        assertTrue(cs[0].call.arguments.isEmpty())
    }

    @Test
    fun malformed_json_yields_empty_args() {
        val events = parser().feedAll("[CALL]foo({bad json})[/CALL]")
        val cs = calls(events)
        assertEquals(1, cs.size)
        assertEquals("foo", cs[0].call.name)
        assertTrue(cs[0].call.arguments.isEmpty())
    }

    @Test
    fun truncated_call_at_end_of_stream() {
        val events = parser().feedAll("preamble.[CALL]read_file({\"path\":\"x")
        assertEquals("preamble.", text(events))
        assertTrue(calls(events).isEmpty())
        assertTrue(events.any { it is MarkerToolStreamParser.Event.Truncated })
    }

    @Test
    fun formatted_result_round_trips() {
        val formatted = MarkerToolStreamParser.formatResult("test-1", true, "hello")
        assertEquals("[RESULT id=test-1 ok=true]hello[/RESULT]", formatted)
    }
}
