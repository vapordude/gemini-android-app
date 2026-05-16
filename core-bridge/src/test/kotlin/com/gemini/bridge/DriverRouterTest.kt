package com.gemini.bridge

import com.gemini.domain.Attachment
import com.gemini.domain.GeminiCore
import com.gemini.domain.GeminiEvent
import com.gemini.domain.GeminiMessage
import com.gemini.domain.GeminiResult
import com.gemini.domain.ToolDecision
import com.gemini.domain.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverRouterTest {

    /** Records which driver received which call. */
    private class FakeCore(val label: String) : GeminiCore {
        var lastSent: String? = null
        var sendCalls: Int = 0
        override suspend fun init(config: Map<String, Any>) = GeminiResult.Success("init $label")
        override suspend fun setProjectFolder(uri: String) = GeminiResult.Success("ok")
        override suspend fun sendMessage(text: String): GeminiResult {
            lastSent = text; sendCalls++
            return GeminiResult.Success("[$label] $text")
        }
        override suspend fun sendMessage(text: String, attachments: List<Attachment>): GeminiResult {
            lastSent = text; sendCalls++
            return GeminiResult.Success("[$label]+${attachments.size}: $text")
        }
        override suspend fun resetSession() = GeminiResult.Success("reset")
        override suspend fun loadHistory(): List<GeminiMessage> = emptyList()
        override val events: Flow<GeminiEvent> = emptyFlow()
        override suspend fun resolveToolDecision(callId: String, decision: ToolDecision) { }
        override fun availableTools(): List<ToolSpec> = emptyList()
    }

    private fun router(
        mode: DriverMode,
        localReady: Boolean = true,
        online: Boolean = true,
    ): Triple<DriverRouter, FakeCore, FakeCore> {
        val local = FakeCore("local")
        val remote = FakeCore("remote")
        val r = DriverRouter(
            local = local,
            remote = remote,
            mode = { mode },
            connectivity = { online },
            isLocalReady = { localReady },
        )
        return Triple(r, local, remote)
    }

    @Test fun safety_first_routes_sensitive_to_local() = runBlocking {
        val (r, local, remote) = router(DriverMode.SAFETY_FIRST)
        val result = r.sendMessage("rotate my id_rsa key")
        assertTrue(result is GeminiResult.Success)
        assertEquals(1, local.sendCalls)
        assertEquals(0, remote.sendCalls)
        assertEquals(DriverRouter.PickReason.SafetySensitive, r.lastPickReason())
    }

    @Test fun safety_first_refuses_when_sensitive_and_local_not_ready() = runBlocking {
        val (r, local, remote) = router(DriverMode.SAFETY_FIRST, localReady = false)
        val result = r.sendMessage("here is my access_token please rotate it")
        assertTrue(result is GeminiResult.Error)
        assertEquals(0, local.sendCalls)
        assertEquals(0, remote.sendCalls)
        assertEquals(DriverRouter.PickReason.SafetyNoLocalRefused, r.lastPickReason())
    }

    @Test fun safety_first_routes_benign_to_remote() = runBlocking {
        val (r, _, remote) = router(DriverMode.SAFETY_FIRST)
        val result = r.sendMessage("refactor this kotlin function for readability")
        assertTrue(result is GeminiResult.Success)
        assertEquals(1, remote.sendCalls)
        assertEquals(DriverRouter.PickReason.RemoteDefault, r.lastPickReason())
    }

    @Test fun safety_first_offline_falls_back_to_local_for_benign() = runBlocking {
        val (r, local, remote) = router(DriverMode.SAFETY_FIRST, online = false)
        val result = r.sendMessage("hello world")
        assertTrue(result is GeminiResult.Success)
        assertEquals(1, local.sendCalls)
        assertEquals(0, remote.sendCalls)
        assertEquals(DriverRouter.PickReason.OfflineFallback, r.lastPickReason())
    }

    @Test fun local_only_routes_everything_local() = runBlocking {
        val (r, local, remote) = router(DriverMode.LOCAL_ONLY)
        r.sendMessage("hello")
        r.sendMessage("my password is hunter2")
        assertEquals(2, local.sendCalls)
        assertEquals(0, remote.sendCalls)
    }

    @Test fun remote_only_routes_everything_remote() = runBlocking {
        val (r, local, remote) = router(DriverMode.REMOTE_ONLY)
        r.sendMessage("hello")
        // Even with a sensitive prompt — user explicitly chose REMOTE_ONLY.
        r.sendMessage("here is my id_rsa")
        assertEquals(2, remote.sendCalls)
        assertEquals(0, local.sendCalls)
    }

    @Test fun auto_online_picks_remote() = runBlocking {
        val (r, local, remote) = router(DriverMode.AUTO, online = true)
        r.sendMessage("anything")
        assertEquals(1, remote.sendCalls)
        assertEquals(0, local.sendCalls)
    }

    @Test fun auto_offline_picks_local() = runBlocking {
        val (r, local, remote) = router(DriverMode.AUTO, online = false)
        r.sendMessage("anything")
        assertEquals(0, remote.sendCalls)
        assertEquals(1, local.sendCalls)
    }

    @Test fun sensitive_text_attachment_routes_local_even_with_safe_prompt() = runBlocking {
        val (r, local, remote) = router(DriverMode.SAFETY_FIRST)
        val envFile = Attachment(
            bytes = "DB_PASSWORD=hunter2\nGITHUB_TOKEN=ghp_xxxx".toByteArray(),
            mimeType = "text/plain",
        )
        val result = r.sendMessage("review this config", listOf(envFile))
        assertTrue(result is GeminiResult.Success)
        assertEquals(1, local.sendCalls)
        assertEquals(0, remote.sendCalls)
        assertEquals(DriverRouter.PickReason.SafetySensitive, r.lastPickReason())
    }
}
