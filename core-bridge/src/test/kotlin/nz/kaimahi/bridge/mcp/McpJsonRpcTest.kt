package nz.kaimahi.bridge.mcp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpJsonRpcTest {

    @Test
    fun request_envelope_has_jsonrpc_and_method() {
        val req = McpJsonRpc.request(42, "tools/list")
        assertEquals("2.0", req.optString("jsonrpc"))
        assertEquals(42L, req.optLong("id"))
        assertEquals("tools/list", req.optString("method"))
    }

    @Test
    fun result_is_extracted_when_ids_match() {
        val resp = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 7L)
            .put("result", JSONObject().put("ok", true))
        val out = McpJsonRpc.parseResult(resp, 7)
        assertTrue(out.optBoolean("ok"))
    }

    @Test
    fun server_error_throws_with_code() {
        val resp = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 7L)
            .put(
                "error",
                JSONObject().put("code", -32601).put("message", "method not found")
            )
        try {
            McpJsonRpc.parseResult(resp, 7)
            fail("expected McpError.Server")
        } catch (e: McpError.Server) {
            assertEquals(-32601, e.code)
            assertTrue(e.message?.contains("method not found") == true)
        }
    }

    @Test
    fun mismatched_id_is_protocol_error() {
        val resp = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 99L)
            .put("result", JSONObject())
        try {
            McpJsonRpc.parseResult(resp, 7)
            fail("expected McpError.Protocol")
        } catch (e: McpError.Protocol) {
            assertTrue(e.message?.contains("99") == true)
        }
    }

    @Test
    fun server_config_round_trips_via_json() {
        val cfg = McpServerConfig(
            id = "weather",
            displayName = "Weather MCP",
            url = "https://example.com/mcp",
            authToken = "secret",
            enabled = false,
        )
        val parsed = McpServerConfig.fromJson(cfg.toJson())!!
        assertEquals(cfg, parsed)
    }

    @Test
    fun server_config_defaults_enabled_true() {
        val o = JSONObject()
            .put("id", "x")
            .put("displayName", "X")
            .put("url", "https://x/mcp")
        val parsed = McpServerConfig.fromJson(o)!!
        assertTrue(parsed.enabled)
        assertEquals(null, parsed.authToken)
    }

    @Test
    fun missing_url_yields_null() {
        val o = JSONObject().put("id", "x").put("displayName", "X")
        assertEquals(null, McpServerConfig.fromJson(o))
    }
}
