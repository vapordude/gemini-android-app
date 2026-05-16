package com.gemini.bridge.patchkernel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Stub HTTP server hand-rolled on top of `ServerSocket` so we don't have
 * to depend on the optional `jdk.httpserver` module — that module isn't
 * always present in the JDK image Gradle's test task forks into.
 *
 * The stub speaks just enough HTTP/1.1 for the client: parses the request
 * line + Content-Length, reads the body, records (method, body), responds
 * 200 with a small JSON payload (or 200 "ok" for /health).
 */
class PatchKernelClientTest {

    private lateinit var server: ServerSocket
    private var port: Int = 0
    private val received = mutableListOf<Pair<String, String>>()
    @Volatile private var running = true

    @Before fun start() {
        server = ServerSocket(0)
        port = server.localPort
        thread(name = "stub-http") {
            while (running) {
                val client = try { server.accept() } catch (_: Exception) { break }
                client.use { sock ->
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    val requestLine = reader.readLine() ?: return@use
                    val parts = requestLine.split(' ')
                    val method = parts.getOrNull(0).orEmpty()
                    val path = parts.getOrNull(1).orEmpty()
                    var contentLength = 0
                    while (true) {
                        val h = reader.readLine() ?: break
                        if (h.isEmpty()) break
                        if (h.lowercase().startsWith("content-length:")) {
                            contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                    }
                    val body = if (contentLength > 0) {
                        val buf = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(buf, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        String(buf, 0, read)
                    } else ""
                    received += method to body

                    val (status, resp) = when {
                        path == "/health" -> 200 to "ok"
                        path == "/mcp" -> 200 to """{"ok":true,"echo":$body}"""
                        else -> 404 to "not found"
                    }
                    val out: OutputStream = sock.getOutputStream()
                    val payload = resp.toByteArray()
                    val statusText = if (status == 200) "OK" else "Not Found"
                    val header = buildString {
                        append("HTTP/1.1 ").append(status).append(' ').append(statusText).append("\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ").append(payload.size).append("\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray())
                    out.write(payload)
                    out.flush()
                }
            }
        }
    }

    @After fun stop() {
        running = false
        runCatching { server.close() }
    }

    @Test fun reachable_when_health_responds() {
        val c = PatchKernelClient("http://127.0.0.1:$port")
        assertTrue(c.isReachable())
    }

    @Test fun unreachable_when_server_isnt_running() {
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
