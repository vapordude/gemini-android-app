package nz.kaimahi.bridge.agent

import nz.kaimahi.domain.ToolCall
import org.json.JSONObject

/**
 * Streaming marker protocol for the local agent loop.
 *
 * Grammar — single-line markers, easy to instruct, easy to parse, won't
 * trip on stray markdown:
 *
 *   `[CALL]tool_name({"arg":"value"})[/CALL]`
 *
 * The host inserts results in the next turn's prompt as:
 *
 *   `[RESULT id=<call-id> ok=true]<output>[/RESULT]`
 *
 * Plain prose outside these markers streams straight to the chat
 * bubble. When the model has a final answer it just writes prose.
 *
 * The parser is **streaming-safe**: it never emits a Text event that
 * contains a partial marker prefix. It keeps a small tail buffer
 * (length = max marker length − 1) until enough context has arrived
 * to decide whether the tail is text or the start of a marker.
 */
class MarkerToolStreamParser(
    /** Override the call-id generator (tests use a counter). */
    private val newCallId: () -> String = { "local-${System.nanoTime()}" },
) {
    sealed class Event {
        data class Text(val text: String) : Event()
        data class Call(val call: ToolCall, val rawArgs: String) : Event()
        /** Model emitted `[CALL]` but stream ended before `[/CALL]`. */
        object Truncated : Event()
    }

    private val buf = StringBuilder()
    private var inCall = false
    private val callBuf = StringBuilder()
    private var done = false

    /** Feed an incoming token chunk. Returns the events the parser
     *  could complete given the buffer state. */
    fun feed(chunk: String): List<Event> {
        if (done) return emptyList()
        if (chunk.isEmpty()) return emptyList()
        buf.append(chunk)
        return drain()
    }

    /** Call once when the underlying stream ends. Flushes any safe
     *  trailing text and either closes cleanly or emits Truncated. */
    fun flush(): List<Event> {
        if (done) return emptyList()
        done = true
        val out = drain(finalChunk = true)
        if (inCall) return out + Event.Truncated
        // Any leftover prose is safe to emit at flush — no more data.
        return if (buf.isNotEmpty()) {
            val text = buf.toString()
            buf.clear()
            out + Event.Text(text)
        } else out
    }

    private fun drain(finalChunk: Boolean = false): List<Event> {
        val out = mutableListOf<Event>()
        // Keep looping until no more complete markers can be made out.
        while (true) {
            if (!inCall) {
                val openIdx = buf.indexOf(OPEN)
                if (openIdx >= 0) {
                    if (openIdx > 0) {
                        out.add(Event.Text(buf.substring(0, openIdx)))
                    }
                    buf.delete(0, openIdx + OPEN.length)
                    inCall = true
                    callBuf.clear()
                    continue
                }
                // No open marker in sight. Emit the safe prefix, hold
                // back enough tail to be sure a prefix isn't beginning
                // there.
                val safe = if (finalChunk) buf.length else (buf.length - SAFE_TAIL).coerceAtLeast(0)
                if (safe > 0) {
                    out.add(Event.Text(buf.substring(0, safe)))
                    buf.delete(0, safe)
                }
                break
            } else {
                val closeIdx = buf.indexOf(CLOSE)
                if (closeIdx >= 0) {
                    callBuf.append(buf, 0, closeIdx)
                    buf.delete(0, closeIdx + CLOSE.length)
                    val parsed = parseCallBody(callBuf.toString())
                    if (parsed != null) out.add(parsed)
                    inCall = false
                    callBuf.clear()
                    continue
                }
                // No close yet — keep accumulating the call body, but
                // leave a tail to be sure we don't chop the close.
                val keep = if (finalChunk) buf.length else (buf.length - SAFE_TAIL).coerceAtLeast(0)
                if (keep > 0) {
                    callBuf.append(buf, 0, keep)
                    buf.delete(0, keep)
                }
                break
            }
        }
        return out
    }

    private fun parseCallBody(raw: String): Event.Call? {
        // Expected shape: name(json)  with optional whitespace.
        val trimmed = raw.trim()
        val lparen = trimmed.indexOf('(')
        val rparen = trimmed.lastIndexOf(')')
        val name = if (lparen > 0) trimmed.substring(0, lparen).trim() else trimmed
        if (name.isEmpty()) return null
        val argsJson = if (lparen > 0 && rparen > lparen) {
            trimmed.substring(lparen + 1, rparen).trim()
        } else "{}"
        val args = parseJsonArgs(argsJson)
        return Event.Call(
            call = ToolCall(id = newCallId(), name = name, arguments = args),
            rawArgs = argsJson,
        )
    }

    private fun parseJsonArgs(json: String): Map<String, Any?> {
        if (json.isBlank()) return emptyMap()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, Any?>()
        for (key in obj.keys()) {
            out[key] = when (val v = obj.opt(key)) {
                JSONObject.NULL -> null
                else -> v
            }
        }
        return out
    }

    companion object {
        const val OPEN = "[CALL]"
        const val CLOSE = "[/CALL]"

        /** Largest tail we hold back so we never emit a partial marker. */
        private val SAFE_TAIL = maxOf(OPEN.length, CLOSE.length) - 1

        /** Format a tool result for re-injection into the model prompt. */
        fun formatResult(callId: String, ok: Boolean, output: String): String =
            "[RESULT id=$callId ok=$ok]$output[/RESULT]"
    }
}
