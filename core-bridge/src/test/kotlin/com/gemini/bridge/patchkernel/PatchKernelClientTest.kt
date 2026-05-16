package com.gemini.bridge.patchkernel

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class PatchKernelClientTest {

    private lateinit var server: HttpServer
    private var port: Int = 0
    /** Records every request body the stub server received. */
    private val received = mutableListOf<Pair<String, String>>()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port

        server.createContext("/health") { ex ->
            ex.sendResponseHeaders(200, 0); ex.responseBody.write("ok".toByteArray()); ex.close()
        }
        server.createContext("/mcp") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            received += ex.requestMethod to body
            val resp = """{"ok":true,"echo":$body}""".toByteArray()
            ex.responseHeaders["Content-Type"] = "application/json"
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.write(resp); ex.close()
        }
        server.start()
    }

    @After fun stop() { server.stop(0) }

    @Test fun reachable_when_health_responds() {
        val c = PatchKernelClient("http://127.0.0.1:$port")
        assertTrue(c.isReachable())
    }

    @Test fun unreachable_when_server_isnt_running() {
        // Use a port nothing is listening on.
        val c = PatchKernelClient("http://127.0.0.1:1")
        assertFalse(c.isReachable())
    }

    @Test fun patch_file_posts_correct_payload() {
        val c = PatchKernelClient("http://127.0.0.1:$port")
        c.patchFile("src/foo.rs", 10, 12, "let x = 1;", "abcdef")
        assertEquals(1, received.size)
        val (method, body) = received.first()
        assertEquals("POST", method)
        assertTrue("name in body", body.contains("\"patch_file\""))
        // org.json may emit `\/` for slashes; tolerate both forms.
        assertTrue("path in body", body.contains("src/foo.rs") || body.contains("src\\/foo.rs"))
        assertTrue("start_line in body", body.contains("\"start_line\":10"))
        assertTrue("end_line in body", body.contains("\"end_line\":12"))
        assertTrue("expected_hash in body", body.contains("\"expected_hash\":\"abcdef\""))
    }

    @Test fun search_symbols_posts_correct_payload() {
        val c = PatchKernelClient("http://127.0.0.1:$port")
        c.searchSymbols("DriverRouter", 5)
        val body = received.first().second
        assertTrue(body.contains("\"search_symbols\""))
        assertTrue(body.contains("\"query\":\"DriverRouter\""))
        assertTrue(body.contains("\"k\":5"))
    }

    @Test fun chunk_session_round_trip() {
        val c = PatchKernelClient("http://127.0.0.1:$port")
        c.chunkStart("big.txt")
        c.chunkAppend("sid", "hello")
        c.chunkCommit("sid")
        assertEquals(3, received.size)
        assertTrue(received[0].second.contains("\"chunk_start\""))
        assertTrue(received[1].second.contains("\"chunk_append\""))
        assertTrue(received[2].second.contains("\"chunk_commit\""))
    }

    @Test fun unreachable_post_throws_kernel_error() {
        val c = PatchKernelClient("http://127.0.0.1:1")
        try {
            c.gitStatus()
            org.junit.Assert.fail("expected KernelError")
        } catch (_: PatchKernelClient.KernelError) {
            // expected
        }
    }
}
