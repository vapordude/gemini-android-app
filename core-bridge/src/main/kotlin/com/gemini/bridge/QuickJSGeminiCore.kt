package com.gemini.bridge

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.cash.quickjs.QuickJs
import com.gemini.domain.GeminiCore
import com.gemini.domain.GeminiMessage
import com.gemini.domain.GeminiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface KotlinFSInterface {
    fun readFile(path: String): String?
    fun writeFile(path: String, content: String): Boolean
    fun listFiles(path: String): Array<String>
    fun execCommand(command: String): String
}

class QuickJSGeminiCore(private val context: Context) : GeminiCore, KotlinFSInterface {

    private var quickJs: QuickJs? = null
    private var projectUri: Uri? = null
    private val jsLock = Mutex()

    override suspend fun init(config: Map<String, Any>): GeminiResult = withContext(Dispatchers.IO) {
        jsLock.withLock {
            try {
                quickJs?.close()
                val js = QuickJs.create()
                quickJs = js

                js.set("KotlinFS", KotlinFSInterface::class.java, this@QuickJSGeminiCore)

                js.evaluate(
                    """
                    globalThis.process = globalThis.process || { cwd: function(){ return '/'; }, chdir: function(_){}, env: {} };
                    globalThis.fs = {
                        readFileSync: function(path) { return KotlinFS.readFile(path); },
                        readdirSync:  function(path) { return KotlinFS.listFiles(path); },
                        writeFileSync: function(path, content) { return KotlinFS.writeFile(path, String(content)); },
                        promises: {
                            readFile:  function(path) { return Promise.resolve(KotlinFS.readFile(path)); },
                            writeFile: function(path, content) { return Promise.resolve(KotlinFS.writeFile(path, String(content))); },
                            readdir:   function(path) { return Promise.resolve(KotlinFS.listFiles(path)); }
                        }
                    };
                    """.trimIndent()
                )

                val bundle = loadBundle()
                js.evaluate(bundle)

                val configJson = JSONObject(config).toString()
                js.evaluate("GeminiBridge.init(${JSONObject.quote(configJson)})")

                GeminiResult.Success("Ready")
            } catch (e: Exception) {
                Log.e(TAG, "init failed", e)
                GeminiResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun loadBundle(): String {
        return try {
            context.assets.open("gemini-core-bundle.mjs").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "gemini-core-bundle.mjs missing, using built-in stub")
            FALLBACK_BUNDLE
        }
    }

    override suspend fun setProjectFolder(uri: String): GeminiResult = withContext(Dispatchers.IO) {
        jsLock.withLock {
            try {
                projectUri = Uri.parse(uri)
                quickJs?.evaluate("process.chdir(${JSONObject.quote(uri)})")
                GeminiResult.Success("Project folder set to $uri")
            } catch (e: Exception) {
                Log.e(TAG, "setProjectFolder failed", e)
                GeminiResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    override suspend fun sendMessage(text: String): GeminiResult = withContext(Dispatchers.IO) {
        jsLock.withLock {
            try {
                val response = quickJs?.evaluate(
                    "GeminiBridge.sendMessage(${JSONObject.quote(text)})"
                )
                GeminiResult.Success(response?.toString() ?: "")
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage failed", e)
                GeminiResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    override suspend fun resetSession(): GeminiResult = withContext(Dispatchers.IO) {
        jsLock.withLock {
            try {
                quickJs?.evaluate("GeminiBridge.resetSession && GeminiBridge.resetSession()")
                GeminiResult.Success("Reset")
            } catch (e: Exception) {
                GeminiResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    override suspend fun loadHistory(): List<GeminiMessage> = withContext(Dispatchers.IO) {
        jsLock.withLock {
            try {
                val raw = quickJs?.evaluate(
                    "JSON.stringify((GeminiBridge.loadHistory && GeminiBridge.loadHistory()) || [])"
                )?.toString() ?: "[]"
                val arr = JSONArray(raw)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    GeminiMessage(
                        id = o.optString("id", i.toString()),
                        text = o.optString("text", ""),
                        isUser = o.optBoolean("isUser", false),
                        timestamp = o.optLong("timestamp", System.currentTimeMillis())
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadHistory failed", e)
                emptyList()
            }
        }
    }

    fun close() {
        try {
            quickJs?.close()
        } catch (_: Exception) {
        }
        quickJs = null
    }

    override fun readFile(path: String): String? {
        val root = rootDoc() ?: return null
        val file = resolve(root, path) ?: return null
        return try {
            context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "readFile($path) failed: ${e.message}")
            null
        }
    }

    override fun writeFile(path: String, content: String): Boolean {
        val root = rootDoc() ?: return false
        val existing = resolve(root, path)
        val target = existing ?: run {
            val parent = resolveParent(root, path) ?: root
            val name = path.substringAfterLast('/')
            parent.createFile("text/plain", name)
        } ?: return false
        return try {
            context.contentResolver.openOutputStream(target.uri)?.use {
                it.write(content.toByteArray())
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "writeFile($path) failed: ${e.message}")
            false
        }
    }

    override fun listFiles(path: String): Array<String> {
        val root = rootDoc() ?: return emptyArray()
        val dir = if (path.isBlank() || path == "/" || path == ".") root else resolve(root, path) ?: return emptyArray()
        return dir.listFiles().mapNotNull { it.name }.toTypedArray()
    }

    override fun execCommand(command: String): String {
        Log.i(TAG, "execCommand requested (not supported on Android): $command")
        return "[execCommand not supported in Android sandbox]"
    }

    private fun rootDoc(): DocumentFile? {
        val uri = projectUri ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    private fun resolve(root: DocumentFile, path: String): DocumentFile? {
        if (path.isBlank() || path == "." || path == "/") return root
        var current: DocumentFile? = root
        path.trim('/').split('/').forEach { segment ->
            if (segment.isEmpty()) return@forEach
            current = current?.findFile(segment)
            if (current == null) return null
        }
        return current
    }

    private fun resolveParent(root: DocumentFile, path: String): DocumentFile? {
        val parentPath = path.trim('/').substringBeforeLast('/', "")
        return if (parentPath.isEmpty()) root else resolve(root, parentPath)
    }

    private companion object {
        const val TAG = "QuickJSGeminiCore"

        val FALLBACK_BUNDLE = """
            globalThis.GeminiBridge = {
                _config: null,
                _history: [],
                init: function(configJson) {
                    try { this._config = JSON.parse(configJson); } catch (e) { this._config = {}; }
                    return 'ok';
                },
                sendMessage: function(text) {
                    this._history.push({ id: String(Date.now()), text: text, isUser: true, timestamp: Date.now() });
                    var reply = '[stub] gemini-core-bundle.mjs is missing. Received: ' + text;
                    this._history.push({ id: String(Date.now()+1), text: reply, isUser: false, timestamp: Date.now() });
                    return reply;
                },
                resetSession: function() { this._history = []; return 'reset'; },
                loadHistory: function() { return this._history; }
            };
        """.trimIndent()
    }
}
