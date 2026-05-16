package nz.kaimahi.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import nz.kaimahi.domain.Attachment
import nz.kaimahi.domain.GeminiCore
import nz.kaimahi.domain.GeminiEvent
import nz.kaimahi.domain.GeminiMessage
import nz.kaimahi.domain.GeminiResult
import nz.kaimahi.domain.ToolDecision
import nz.kaimahi.domain.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * Pairs the local Rust Gemma 4 driver with the remote gemini-cli driver and
 * picks one per turn. Picking is decided by [DriverMode]:
 *
 * - [DriverMode.SAFETY_FIRST] (default): sensitive prompts route to the
 *   local driver; everything else goes to remote (Gemini). When the
 *   prompt is sensitive AND the local driver isn't ready, the call is
 *   *refused* rather than leaked — the user gets a clear "local driver
 *   unavailable for sensitive content" error and can decide what to do.
 * - [DriverMode.LOCAL_ONLY]: always local. Errors out if local isn't ready.
 * - [DriverMode.REMOTE_ONLY]: always remote, regardless of sensitivity.
 *   Useful when the user explicitly wants to send a credential to the
 *   cloud (e.g. asking how to rotate it) and has accepted the trade-off.
 * - [DriverMode.AUTO]: prefers remote when online, local when offline.
 *   Ignores sensitivity — for users who've turned off the safety check.
 *
 * Conversation history, tool decisions, and attachments live above the
 * router, so a single chat can be served by either driver across turns.
 */
class DriverRouter(
    private val local: GeminiCore,
    private val remote: GeminiCore,
    private val mode: () -> DriverMode,
    private val connectivity: () -> Boolean,
    private val isLocalReady: () -> Boolean,
    private val classify: (String) -> SensitivityClassifier.Classification =
        SensitivityClassifier::classify,
) : GeminiCore {

    /** Driver that served the most recent successful pick. Reported via [lastDriverLabel]. */
    @Volatile private var lastPicked: GeminiCore = remote
    @Volatile private var lastReason: PickReason = PickReason.RemoteDefault

    /** Why the most recent turn was routed where it was. Surfaced in the UI badge. */
    enum class PickReason {
        SafetySensitive,        // detected sensitive content → local
        SafetyNoLocalRefused,   // sensitive + local not ready → refused
        OfflineFallback,        // AUTO + no network → local
        UserPinnedLocal,        // LOCAL_ONLY
        UserPinnedRemote,       // REMOTE_ONLY
        RemoteDefault,          // SAFETY_FIRST/AUTO online, not sensitive → remote
    }

    private data class Pick(val driver: GeminiCore?, val reason: PickReason, val refusalText: String? = null)

    private fun pick(text: String): Pick {
        val current = mode()
        // Explicit user pins bypass sensitivity.
        when (current) {
            DriverMode.LOCAL_ONLY -> return Pick(local, PickReason.UserPinnedLocal)
            DriverMode.REMOTE_ONLY -> return Pick(remote, PickReason.UserPinnedRemote)
            DriverMode.AUTO -> {
                return if (connectivity()) Pick(remote, PickReason.RemoteDefault)
                else Pick(local, PickReason.OfflineFallback)
            }
            DriverMode.SAFETY_FIRST -> { /* fall through */ }
        }
        // SAFETY_FIRST: classify, then choose.
        val cls = classify(text)
        return if (cls.isSensitive) {
            if (isLocalReady()) {
                Pick(local, PickReason.SafetySensitive)
            } else {
                Pick(
                    null,
                    PickReason.SafetyNoLocalRefused,
                    refusalText = buildRefusal(cls),
                )
            }
        } else {
            // Fall back to local when there's no network at all.
            if (!connectivity() && isLocalReady()) Pick(local, PickReason.OfflineFallback)
            else Pick(remote, PickReason.RemoteDefault)
        }
    }

    private fun buildRefusal(cls: SensitivityClassifier.Classification): String {
        val cats = cls.categories.joinToString(", ") { it.name }
        val hit = cls.matchedPhrases.take(3).joinToString(", ") { "\"$it\"" }
        return "This prompt matched on-device-only categories ($cats) " +
            "based on substrings: $hit. The local Gemma 4 driver isn't packaged " +
            "in this build, so I won't send it to the remote model. " +
            "Either install the local model, or switch to Remote-only mode in " +
            "Settings if you intentionally want to share this with Gemini."
    }

    override suspend fun init(config: Map<String, Any>): GeminiResult {
        // Init both. If neither initializes we bubble up an error.
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
        // Workspace is shared — set on both. If local can't, that's not fatal.
        val r = remote.setProjectFolder(uri)
        runCatching { local.setProjectFolder(uri) }
        return r
    }

    override suspend fun sendMessage(text: String): GeminiResult {
        val p = pick(text)
        lastReason = p.reason
        val driver = p.driver
            ?: return GeminiResult.Error(p.refusalText ?: "Routing refused")
        lastPicked = driver
        return driver.sendMessage(text)
    }

    override suspend fun sendMessage(text: String, attachments: List<Attachment>): GeminiResult {
        // Inspect the prompt plus any text-shaped attachments (.txt, .md,
        // .env, .pem etc.). Binary attachments don't contribute to the
        // classifier signal but the prompt text is the dominant carrier.
        val classifyInput = buildString {
            append(text)
            for (att in attachments) {
                if (att.mimeType.startsWith("text/") || att.mimeType.endsWith("/json")) {
                    runCatching { String(att.bytes, Charsets.UTF_8) }
                        .onSuccess { append("\n").append(it) }
                }
            }
        }
        val p = pick(classifyInput)
        lastReason = p.reason
        val driver = p.driver
            ?: return GeminiResult.Error(p.refusalText ?: "Routing refused")
        lastPicked = driver
        return driver.sendMessage(text, attachments)
    }

    override suspend fun resetSession(): GeminiResult {
        local.resetSession()
        return remote.resetSession()
    }

    override suspend fun setSystemPrompt(prompt: String?) {
        local.setSystemPrompt(prompt)
        remote.setSystemPrompt(prompt)
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

    /** Driver that served the most recent turn — for the chat header badge. */
    fun lastDriverLabel(): String = when (lastPicked) {
        local -> "Local (Gemma 4 E2B)"
        else -> "Remote (Gemini)"
    }

    fun lastPickReason(): PickReason = lastReason
}

enum class DriverMode {
    /** Sensitive prompts → local (or refuse if local not ready). Else remote. */
    SAFETY_FIRST,
    /** Always the local Rust Gemma 4 driver. */
    LOCAL_ONLY,
    /** Always Gemini, regardless of sensitivity. */
    REMOTE_ONLY,
    /** Online → remote, offline → local. Ignores sensitivity. */
    AUTO;

    companion object {
        fun parse(s: String?): DriverMode = when (s?.uppercase()) {
            "LOCAL_ONLY", "LOCAL" -> LOCAL_ONLY
            "REMOTE_ONLY", "REMOTE" -> REMOTE_ONLY
            "AUTO" -> AUTO
            else -> SAFETY_FIRST
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
