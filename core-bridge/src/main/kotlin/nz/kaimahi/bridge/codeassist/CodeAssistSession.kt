package nz.kaimahi.bridge.codeassist

import android.content.Context
import nz.kaimahi.bridge.auth.GeminiCliAuthService
import nz.kaimahi.bridge.storage.OAuthTokens
import nz.kaimahi.bridge.storage.SecurePrefs

/**
 * Wraps a [CodeAssistClient] with token bookkeeping. Owns the in-memory
 * [OAuthTokens] for the current sign-in, persists refreshes back into
 * [SecurePrefs], and on first call runs `loadCodeAssist` (+ `onboardUser`
 * if needed) so we always have a `cloudaicompanionProject` cached.
 *
 * The Kotlin side only needs to construct one of these per signed-in
 * gemini-cli session and call [streamGenerateContent] / [countTokens];
 * the refresh + onboarding plumbing lives behind it.
 */
class CodeAssistSession(
    context: Context,
    private val prefs: SecurePrefs,
    initial: OAuthTokens,
) {
    @Volatile private var tokens: OAuthTokens = initial
    private val auth = GeminiCliAuthService(context.applicationContext)
    private val client = CodeAssistClient(
        tokens = { tokens },
        onRefreshNeeded = ::refreshAndPersist,
    )

    /** Run loadCodeAssist + onboardUser if no project id is cached yet. */
    suspend fun ensureOnboarded(): String {
        prefs.codeAssistProjectId?.takeIf { it.isNotBlank() }?.let { return it }
        // Refresh first if the access token is close to expiry — the
        // `loadCodeAssist` POST would otherwise just bounce on 401 and
        // we'd do the same work after one round trip anyway.
        if (tokens.expiresWithin()) {
            refreshAndPersist()
        }
        val load = client.loadCodeAssist()
        val tier = load.currentTier ?: load.allowedTiers.firstOrNull() ?: "free-tier"
        val project = if (!load.isOnboarded) {
            val r = client.onboardUser(tier, load.cloudaicompanionProject)
            r.projectId
        } else {
            load.cloudaicompanionProject
        }
        val projectId = project
            ?: throw IllegalStateException("Code Assist returned no project id (tier=$tier)")
        prefs.codeAssistProjectId = projectId
        tokens = tokens.copy(projectId = projectId)
        return projectId
    }

    suspend fun streamGenerateContent(
        model: String,
        sessionId: String,
        request: org.json.JSONObject,
        onChunk: suspend (GenerateContentChunk) -> Unit,
    ) {
        val project = ensureOnboarded()
        client.streamGenerateContent(model, sessionId, project, request, onChunk)
    }

    suspend fun countTokens(model: String, request: org.json.JSONObject): Int =
        client.countTokens(model, request)

    /**
     * Force a refresh round-trip and persist the new access token /
     * expiry. Called both from [ensureOnboarded] (preemptively) and from
     * [CodeAssistClient]'s 401 retry path.
     */
    private suspend fun refreshAndPersist(): OAuthTokens? {
        val refreshed = auth.refresh(tokens) ?: return null
        tokens = refreshed
        prefs.accessToken = refreshed.accessToken
        prefs.refreshToken = refreshed.refreshToken
        prefs.tokenExpiryMs = refreshed.expiryEpochMs
        refreshed.projectId?.takeIf { it.isNotBlank() }?.let { prefs.codeAssistProjectId = it }
        return refreshed
    }

    /** Current snapshot — useful for callers that need the bearer token directly. */
    fun currentTokens(): OAuthTokens = tokens
}
