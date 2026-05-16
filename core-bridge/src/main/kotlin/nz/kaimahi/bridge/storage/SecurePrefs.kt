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

    var accessToken: String?
        get() = encrypted.getString(KEY_ACCESS_TOKEN, null)
        set(value) = encrypted.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_ACCESS_TOKEN) else putString(KEY_ACCESS_TOKEN, value)
        }.apply()

    var refreshToken: String?
        get() = encrypted.getString(KEY_REFRESH_TOKEN, null)
        set(value) = encrypted.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_REFRESH_TOKEN) else putString(KEY_REFRESH_TOKEN, value)
        }.apply()

    /** Epoch ms at which [accessToken] expires. 0 == unknown. */
    var tokenExpiryMs: Long
        get() = encrypted.getLong(KEY_TOKEN_EXPIRY, 0L)
        set(value) = encrypted.edit().apply {
            if (value <= 0L) remove(KEY_TOKEN_EXPIRY) else putLong(KEY_TOKEN_EXPIRY, value)
        }.apply()

    /**
     * Cloud AI Companion project id resolved by `loadCodeAssist` /
     * `onboardUser`. Required for every Code Assist request, cached here
     * so we don't onboard on every session.
     */
    var codeAssistProjectId: String?
        get() = encrypted.getString(KEY_CA_PROJECT, null)
        set(value) = encrypted.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_CA_PROJECT) else putString(KEY_CA_PROJECT, value)
        }.apply()

    /**
     * True when the persisted access token belongs to the gemini-cli /
     * Code Assist flow rather than the public Gemini API. Determines
     * which endpoint (and which refresh flow) `RestGeminiCore` uses.
     */
    var useCodeAssist: Boolean
        get() = plain.getBoolean(KEY_USE_CODE_ASSIST, false)
        set(value) = plain.edit().putBoolean(KEY_USE_CODE_ASSIST, value).apply()

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

    var localModelPath: String?
        get() = plain.getString(KEY_LOCAL_MODEL_PATH, null)
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_LOCAL_MODEL_PATH) else putString(KEY_LOCAL_MODEL_PATH, value)
        }.apply()

    var homeTreeUri: String?
        get() = plain.getString(KEY_HOME_TREE_URI, null)
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_HOME_TREE_URI) else putString(KEY_HOME_TREE_URI, value)
        }.apply()

    var documentsTreeUri: String?
        get() = plain.getString(KEY_DOCUMENTS_TREE_URI, null)
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_DOCUMENTS_TREE_URI) else putString(KEY_DOCUMENTS_TREE_URI, value)
        }.apply()

    var downloadsTreeUri: String?
        get() = plain.getString(KEY_DOWNLOADS_TREE_URI, null)
        set(value) = plain.edit().apply {
            if (value.isNullOrBlank()) remove(KEY_DOWNLOADS_TREE_URI) else putString(KEY_DOWNLOADS_TREE_URI, value)
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
        const val KEY_TOKEN_EXPIRY = "token_expiry_ms"
        const val KEY_CA_PROJECT = "code_assist_project"
        const val KEY_USE_CODE_ASSIST = "use_code_assist"
        const val KEY_MODEL = "model"
        const val KEY_IMAGEN_MODEL = "imagen_model"
        const val KEY_WORKSPACE = "workspace_uri"
        const val KEY_LOCAL_MODEL_PATH = "local_model_path"
        const val KEY_HOME_TREE_URI = "home_tree_uri"
        const val KEY_DOCUMENTS_TREE_URI = "documents_tree_uri"
        const val KEY_DOWNLOADS_TREE_URI = "downloads_tree_uri"
        const val KEY_AUTO_APPROVE = "auto_approve"
        const val KEY_TERMUX_GUIDE_SHOWN = "termux_guide_shown"
        const val KEY_AUTO_COMPRESS = "auto_compress_enabled"
        const val KEY_AUTO_COMPRESS_THRESHOLD = "auto_compress_threshold"
        const val KEY_AUTO_SAVE = "auto_save_enabled"
    }
}
