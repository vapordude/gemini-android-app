package nz.kaimahi.bridge.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Small wrapper around SharedPreferences with AES-GCM encryption.
 * Used for the Gemini API key; non-sensitive preferences (model, workspace URI)
 * live in a plain file alongside it.
 */
class SecurePrefs(context: Context) {

    private val master = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encrypted: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "gemini-secrets",
        master,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val plain: SharedPreferences = context.getSharedPreferences("gemini-prefs", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = encrypted.getString(KEY_API, null)
        set(value) = encrypted.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_API) else putString(KEY_API, value)
        }.apply()

    /**
     * Legacy single-token accessor. New code reads [oauthTokens]; this remains
     * so callers that just need a Bearer string keep working while the migration
     * is in flight.
     */
    var accessToken: String?
        get() = encrypted.getString(KEY_ACCESS_TOKEN, null)
        set(value) = encrypted.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_ACCESS_TOKEN) else putString(KEY_ACCESS_TOKEN, value)
        }.apply()

    /**
     * Full OAuth credential set, matching gemini-cli's `oauth_creds.json`. The
     * refresh token is the durable credential; access tokens expire every hour.
     * `projectId` is the Code Assist project resolved at first `loadCodeAssist`.
     */
    var oauthTokens: OAuthTokens?
        get() {
            val access = encrypted.getString(KEY_ACCESS_TOKEN, null) ?: return null
            val refresh = encrypted.getString(KEY_REFRESH_TOKEN, null) ?: return null
            val expiry = encrypted.getLong(KEY_TOKEN_EXPIRY, 0L)
            val project = encrypted.getString(KEY_PROJECT_ID, null)
            return OAuthTokens(access, refresh, expiry, project)
        }
        set(value) = encrypted.edit().apply {
            if (value == null) {
                remove(KEY_ACCESS_TOKEN); remove(KEY_REFRESH_TOKEN)
                remove(KEY_TOKEN_EXPIRY); remove(KEY_PROJECT_ID)
            } else {
                putString(KEY_ACCESS_TOKEN, value.accessToken)
                putString(KEY_REFRESH_TOKEN, value.refreshToken)
                putLong(KEY_TOKEN_EXPIRY, value.expiryEpochMs)
                if (value.projectId.isNullOrBlank()) remove(KEY_PROJECT_ID)
                else putString(KEY_PROJECT_ID, value.projectId)
            }
        }.apply()

    /**
     * Stored as the [nz.kaimahi.bridge.DriverMode] name. Default is
     * `SAFETY_FIRST` so users are protected from accidentally leaking
     * credentials/secrets/SSH keys to the remote API.
     */
    var driverMode: String
        get() = plain.getString(KEY_DRIVER_MODE, "SAFETY_FIRST") ?: "SAFETY_FIRST"
        set(value) = plain.edit().putString(KEY_DRIVER_MODE, value).apply()

    var model: String?
        get() = plain.getString(KEY_MODEL, null)
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_MODEL) else putString(KEY_MODEL, value)
        }.apply()

    var imagenModel: String?
        get() = plain.getString(KEY_IMAGEN_MODEL, null)
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_IMAGEN_MODEL) else putString(KEY_IMAGEN_MODEL, value)
        }.apply()

    var workspaceUri: String?
        get() = plain.getString(KEY_WORKSPACE, null)
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_WORKSPACE) else putString(KEY_WORKSPACE, value)
        }.apply()

    /**
     * SAF tree URIs the user has granted for the standard Home / Documents /
     * Downloads scopes. Each is set via the per-scope picker in Settings; the
     * URI carries persistable read permission (taken via
     * `ContentResolver.takePersistableUriPermission` at the call site).
     * Empty/null means "not granted" — [nz.kaimahi.bridge.tools.ListUserFilesTool]
     * surfaces a clear error in that case rather than silently failing.
     */
    var homeTreeUri: String?
        get() = plain.getString(KEY_HOME_URI, null)
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_HOME_URI) else putString(KEY_HOME_URI, value)
        }.apply()

    var documentsTreeUri: String?
        get() = plain.getString(KEY_DOCS_URI, null)
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_DOCS_URI) else putString(KEY_DOCS_URI, value)
        }.apply()

    var downloadsTreeUri: String?
        get() = plain.getString(KEY_DOWN_URI, null)
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_DOWN_URI) else putString(KEY_DOWN_URI, value)
        }.apply()

    /**
     * Base URL for the **Patch Kernel** sidecar. Empty means "off" — the
     * kernel tools are then not registered and the agent uses only the
     * built-in file/shell tools. Default points at the local Termux
     * install (`http://127.0.0.1:7979`).
     */
    var patchKernelUrl: String?
        get() = plain.getString(KEY_KERNEL_URL, "http://127.0.0.1:7979")
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_KERNEL_URL) else putString(KEY_KERNEL_URL, value)
        }.apply()

    /** Optional bearer token for the Patch Kernel (`MCP_AUTH_TOKEN` on the
     *  kernel side). Encrypted because it may grant write access. */
    var patchKernelAuthToken: String?
        get() = encrypted.getString(KEY_KERNEL_TOKEN, null)
        set(value) = encrypted.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_KERNEL_TOKEN) else putString(KEY_KERNEL_TOKEN, value)
        }.apply()

    var autoApprove: Boolean
        get() = plain.getBoolean(KEY_AUTO_APPROVE, false)
        set(value) = plain.edit().putBoolean(KEY_AUTO_APPROVE, value).apply()

    var termuxGuideShown: Boolean
        get() = plain.getBoolean(KEY_TERMUX_GUIDE_SHOWN, false)
        set(value) = plain.edit().putBoolean(KEY_TERMUX_GUIDE_SHOWN, value).apply()

    var autoCompressEnabled: Boolean
        get() = plain.getBoolean(KEY_AUTO_COMPRESS, true)
        set(value) = plain.edit().putBoolean(KEY_AUTO_COMPRESS, value).apply()

    var autoSaveEnabled: Boolean
        get() = plain.getBoolean(KEY_AUTO_SAVE, true)
        set(value) = plain.edit().putBoolean(KEY_AUTO_SAVE, value).apply()

    /** Fraction of the model's input-token limit at which to auto-compress (0.5..0.95). */
    var autoCompressThreshold: Float
        get() = plain.getFloat(KEY_AUTO_COMPRESS_THRESHOLD, 0.7f)
        set(value) = plain.edit().putFloat(KEY_AUTO_COMPRESS_THRESHOLD, value).apply()

    fun clearAll() {
        encrypted.edit().clear().apply()
        plain.edit().clear().apply()
    }

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_TOKEN_EXPIRY = "token_expiry_epoch_ms"
        const val KEY_PROJECT_ID = "code_assist_project_id"
        const val KEY_MODEL = "model"
        const val KEY_IMAGEN_MODEL = "imagen_model"
        const val KEY_WORKSPACE = "workspace_uri"
        const val KEY_AUTO_APPROVE = "auto_approve"
        const val KEY_TERMUX_GUIDE_SHOWN = "termux_guide_shown"
        const val KEY_AUTO_COMPRESS = "auto_compress_enabled"
        const val KEY_AUTO_COMPRESS_THRESHOLD = "auto_compress_threshold"
        const val KEY_AUTO_SAVE = "auto_save_enabled"
        const val KEY_DRIVER_MODE = "driver_mode"
        const val KEY_HOME_URI = "home_tree_uri"
        const val KEY_DOCS_URI = "documents_tree_uri"
        const val KEY_DOWN_URI = "downloads_tree_uri"
        const val KEY_KERNEL_URL = "patch_kernel_url"
        const val KEY_KERNEL_TOKEN = "patch_kernel_auth_token"
    }
}

/**
 * Mirrors gemini-cli's `oauth_creds.json` shape: access + refresh + absolute
 * expiry. `projectId` is resolved from `loadCodeAssist` and cached so we don't
 * re-onboard every cold start.
 */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiryEpochMs: Long,
    val projectId: String?,
) {
    /** True when the access token will expire within [skewMs] milliseconds. */
    fun expiresWithin(skewMs: Long = 5 * 60 * 1000L): Boolean =
        System.currentTimeMillis() + skewMs >= expiryEpochMs
}
