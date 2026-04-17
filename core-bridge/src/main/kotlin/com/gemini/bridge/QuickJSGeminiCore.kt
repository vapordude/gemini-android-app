package com.gemini.bridge

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.cash.quickjs.QuickJs
import com.gemini.domain.GeminiCore
import com.gemini.domain.GeminiMessage
import com.gemini.domain.GeminiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class QuickJSGeminiCore(private val context: Context) : GeminiCore {
    private var quickJs: QuickJs? = null
    private var projectUri: Uri? = null

    override suspend fun init(config: Map<String, Any>): GeminiResult = withContext(Dispatchers.IO) {
        try {
            quickJs = QuickJs.create()
            
            // On charge le bundle JS
            val bundle = context.assets.open("gemini-core-bundle.mjs").bufferedReader().use { it.readText() }
            
            // Bridge FS: On expose des fonctions natives pour la lecture de fichiers
            quickJs?.set("KotlinFS", KotlinFSInterface::class.java, object : KotlinFSInterface {
                override fun readFile(path: String): String? {
                    return readProjectFile(path)
                }
                override fun listFiles(path: String): Array<String> {
                    return listProjectFiles(path)
                }
            })

            // On définit le polyfill 'fs' en JS qui appelle 'KotlinFS'
            quickJs?.evaluate("""
                globalThis.fs = {
                    readFileSync: (path) => KotlinFS.readFile(path),
                    readdirSync: (path) => KotlinFS.listFiles(path),
                    promises: {
                        readFile: async (path) => KotlinFS.readFile(path)
                    }
                };
            """)

            quickJs?.evaluate(bundle)
            val configJson = JSONObject(config).toString()
            quickJs?.evaluate("GeminiBridge.init('$configJson')")
            
            GeminiResult.Success("Ready")
        } catch (e: Exception) {
            GeminiResult.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun setProjectFolder(uri: String): GeminiResult {
        projectUri = Uri.parse(uri)
        // On notifie le Core JS du changement de répertoire de travail
        quickJs?.evaluate("process.chdir('$uri')")
        return GeminiResult.Success("Project folder set to $uri")
    }

    private fun readProjectFile(path: String): String? {
        val root = projectUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return null
        // Logique de recherche récursive du fichier par son chemin
        val file = root.findFile(path)
        return context.contentResolver.openInputStream(file?.uri ?: return null)?.bufferedReader()?.readText()
    }

    private fun listProjectFiles(path: String): Array<String> {
        val root = projectUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return emptyArray()
        return root.listFiles().map { it.name ?: "" }.toTypedArray()
    }

    // Interface pour QuickJS
    interface KotlinFSInterface {
        fun readFile(path: String): String?
        fun writeFile(path: String, content: String): Boolean
        fun listFiles(path: String): Array<String>
        fun execCommand(command: String): String
    }

    private fun writeProjectFile(path: String, content: String): Boolean {
        val root = projectUri?.let { DocumentFile.fromTreeUri(context, it) } ?: return false
        val file = root.findFile(path) ?: root.createFile("text/plain", path)
        return try {
            context.contentResolver.openOutputStream(file?.uri ?: return false)?.use {
                it.write(content.toByteArray())
            }
            true
        } catch (e: Exception) { false }
    }

    override suspend fun sendMessage(text: String): GeminiResult = withContext(Dispatchers.IO) {
        try {
            val response = quickJs?.evaluate("GeminiBridge.sendMessage('$text')")
            GeminiResult.Success(response.toString())
        } catch (e: Exception) {
            GeminiResult.Error(e.message ?: "Unknown error")
        }
    }

    override suspend fun resetSession(): GeminiResult = GeminiResult.Success("Reset")
    override suspend fun loadHistory(): List<GeminiMessage> = emptyList()
}
