package com.gemini.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.gemini.domain.GeminiCore
import com.gemini.domain.GeminiEvent
import com.gemini.domain.GeminiMessage
import com.gemini.domain.GeminiResult
import com.gemini.domain.ToolDecision
import com.gemini.domain.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * Selects which backing [GeminiCore] handles each turn. The picker chooses
 * **once per turn** — a streaming response never splits between drivers.
 *
 * - [DriverMode.LOCAL_ONLY]: always the Rust Gemma 4 driver.
 * - [DriverMode.REMOTE_ONLY]: always Gemini-via-OAuth (or API key).
 * - [DriverMode.AUTO]: prefers remote; falls back to local when the network
 *   is unreachable.
 *
 * Conversation history, attachments, and tool decisions are owned above the
 * router, so a single chat can be served by different drivers across turns.
 */
class DriverRouter(
    private val local: GeminiCore,
    private val remote: GeminiCore,
    private val mode: () -> DriverMode,
    private val connectivity: () -> Boolean,
) : GeminiCore {

    /** Driver chosen for the *current* turn — set inside [sendMessage]. */
    @Volatile private var lastPicked: GeminiCore = remote

    private fun pick(): GeminiCore {
        val chosen = when (mode()) {
            DriverMode.LOCAL_ONLY -> local
            DriverMode.REMOTE_ONLY -> remote
            DriverMode.AUTO -> if (connectivity()) remote else local
        }
        lastPicked = chosen
        return chosen
    }

    override suspend fun init(config: Map<String, Any>): GeminiResult {
        // Init both so they can pick up any persisted credentials. If one
        // fails (e.g. local has no weights), the other still works.
        val rRemote = runCatching { remote.init(config) }.getOrNull()
        val rLocal = runCatching { local.init(config) }.getOrNull()
        return when {
            rRemote is GeminiResult.Success -> rRemote
            rLocal is GeminiResult.Success -> rLocal
            rRemote is GeminiResult.Error -> rRemote
            rLocal is GeminiResult.Error -> rLocal
            else -> GeminiResult.Error("No driver could initialize")
        }
    }

    override suspend fun setProjectFolder(uri: String): GeminiResult {
        // Workspace is shared — set on both so either driver sees the same root.
        val r = remote.setProjectFolder(uri)
        runCatching { local.setProjectFolder(uri) }
        return r
    }

    override suspend fun sendMessage(text: String): GeminiResult = pick().sendMessage(text)

    override suspend fun resetSession(): GeminiResult {
        local.resetSession()
        return remote.resetSession()
    }

    override suspend fun loadHistory(): List<GeminiMessage> = lastPicked.loadHistory()

    override val events: Flow<GeminiEvent> = merge(local.events, remote.events)

    override suspend fun resolveToolDecision(callId: String, decision: ToolDecision) {
        // Routing per-call is unknown here; forward to both. Whichever has
        // the call id outstanding picks it up; the other becomes a no-op.
        local.resolveToolDecision(callId, decision)
        remote.resolveToolDecision(callId, decision)
    }

    override fun availableTools(): List<ToolSpec> = remote.availableTools()

    /** The driver that served the most recent [sendMessage]. UI uses this for the mode badge. */
    fun lastDriverLabel(): String = when (lastPicked) {
        local -> "Local (Gemma 4 E2B)"
        else -> "Remote (Gemini)"
    }
}

enum class DriverMode {
    LOCAL_ONLY, REMOTE_ONLY, AUTO;

    companion object {
        fun parse(s: String?): DriverMode = when (s?.uppercase()) {
            "LOCAL_ONLY", "LOCAL" -> LOCAL_ONLY
            "AUTO" -> AUTO
            else -> REMOTE_ONLY
        }
    }
}

/** Quick check for network availability — used by [DriverMode.AUTO]. */
object NetworkInfo {
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
