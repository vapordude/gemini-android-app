package nz.kaimahi.domain

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mirrors the Rust-side tests in `agent_core::multi`. If one side
 * drifts, the other notices.
 */
class MultiBackendTest {

    private class StaticBackend(
        override val name: String,
        private val replies: List<BackendResult>,
    ) : InferenceBackend {
        private var idx = 0
        override suspend fun complete(prompt: String, stop: List<String>): BackendResult {
            val r = replies[idx.coerceAtMost(replies.lastIndex)]
            idx++
            return r
        }
    }

    private fun ok(name: String, text: String) =
        StaticBackend(name, listOf(BackendResult.Ok(text)))

    private fun err(name: String, msg: String, kind: ErrorKind = ErrorKind.Inference) =
        StaticBackend(
            name,
            listOf(
                BackendResult.Failed(AgentError(kind = kind, source = name, message = msg))
            ),
        )

    @Test
    fun preferFirstSucceeds() = runBlocking {
        val m = MultiBackend(
            listOf(ok("cloud", "hi"), ok("local", "ignored")),
            MultiBackend.Policy.PreferFirst,
        )
        val r = m.complete("p")
        assertTrue(r is BackendResult.Ok)
        assertEquals("hi", (r as BackendResult.Ok).text)
    }

    @Test
    fun fallsBackOnError() = runBlocking {
        val m = MultiBackend(
            listOf(err("cloud", "rate-limited"), ok("local", "local-ans")),
            MultiBackend.Policy.PreferFirst,
        )
        val r = m.complete("p")
        assertTrue(r is BackendResult.Ok)
        assertEquals("local-ans", (r as BackendResult.Ok).text)
    }

    @Test
    fun allFailReturnsLastTaggedErr() = runBlocking {
        val m = MultiBackend(
            listOf(err("cloud", "rate"), err("local", "oom")),
            MultiBackend.Policy.PreferFirst,
        )
        val r = m.complete("p")
        assertTrue(r is BackendResult.Failed)
        val msg = (r as BackendResult.Failed).error.source
        assertTrue(msg.contains("local"), "expected last backend in error source, got: $msg")
    }

    @Test
    fun roundRobinAlternates() = runBlocking {
        val m = MultiBackend(
            listOf(
                StaticBackend("a", listOf(BackendResult.Ok("A"), BackendResult.Ok("A"))),
                StaticBackend("b", listOf(BackendResult.Ok("B"), BackendResult.Ok("B"))),
            ),
            MultiBackend.Policy.RoundRobin,
        )
        val first = (m.complete("p") as BackendResult.Ok).text
        val second = (m.complete("p") as BackendResult.Ok).text
        assertTrue(first != second, "round-robin should alternate; got $first then $second")
    }
}
