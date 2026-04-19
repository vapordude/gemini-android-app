package com.gemini.bridge.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Workspace rooted at either a [File] on the app's external files dir (the
 * default, requires no permission) or a SAF tree URI the user picked. Every
 * tool path is resolved as a relative path under the root — absolute paths
 * and `..` traversal are rejected.
 */
class Workspace(private val context: Context) {

    data class Entry(val name: String, val path: String, val isDir: Boolean, val size: Long)

    private var root: DocumentFile? = null
    private var label: String = "(no folder)"

    init {
        val defaultDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "workspace")
        if (!defaultDir.exists()) defaultDir.mkdirs()
        setFile(defaultDir)
    }

    fun rootLabel(): String = label

    fun setFile(file: File) {
        val created = file.apply { if (!exists()) mkdirs() }
        root = DocumentFile.fromFile(created)
        label = created.absolutePath
    }

    fun setTreeUri(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        val tree = DocumentFile.fromTreeUri(context, uri)
            ?: error("Cannot open folder: $uri")
        root = tree
        label = tree.name ?: uri.toString()
    }

    fun resolve(path: String): DocumentFile? {
        val safe = sanitize(path) ?: return null
        if (safe.isEmpty()) return root
        var cur = root ?: return null
        for (segment in safe) {
            val next = cur.findFile(segment) ?: return null
            cur = next
        }
        return cur
    }

    fun read(path: String): String {
        val file = resolve(path) ?: throw IllegalArgumentException("Not found: $path")
        if (file.isDirectory) throw IllegalArgumentException("Is a directory: $path")
        return context.contentResolver.openInputStream(file.uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("Cannot read $path")
    }

    fun write(path: String, content: String): Entry {
        val parts = sanitize(path) ?: throw IllegalArgumentException("Invalid path: $path")
        if (parts.isEmpty()) throw IllegalArgumentException("Refusing to write to root")
        var cur = root ?: throw IllegalStateException("No workspace root")
        val fileName = parts.last()
        for (segment in parts.dropLast(1)) {
            cur = cur.findFile(segment) ?: cur.createDirectory(segment)
                ?: throw IllegalStateException("Cannot create directory: $segment")
        }
        val existing = cur.findFile(fileName)
        val target = existing ?: cur.createFile(mimeFor(fileName), fileName)
            ?: throw IllegalStateException("Cannot create file: $fileName")

        context.contentResolver.openOutputStream(target.uri, "wt")?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Cannot write $path")

        return Entry(
            name = target.name ?: fileName,
            path = parts.joinToString("/"),
            isDir = false,
            size = target.length()
        )
    }

    fun list(path: String): List<Entry> {
        val dir = resolve(path) ?: throw IllegalArgumentException("Not found: $path")
        if (!dir.isDirectory) throw IllegalArgumentException("Not a directory: $path")
        val prefix = sanitize(path)?.joinToString("/")?.ifEmpty { null }
        return dir.listFiles().map {
            val name = it.name ?: "?"
            Entry(
                name = name,
                path = if (prefix == null) name else "$prefix/$name",
                isDir = it.isDirectory,
                size = if (it.isDirectory) 0L else it.length()
            )
        }.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    fun delete(path: String): Boolean {
        val file = resolve(path) ?: return false
        return file.delete()
    }

    fun walk(path: String, maxEntries: Int = 2000): Sequence<Entry> = sequence {
        val start = resolve(path) ?: return@sequence
        val startParts = sanitize(path) ?: return@sequence
        val stack = ArrayDeque<Pair<DocumentFile, List<String>>>()
        stack.addLast(start to startParts)
        var count = 0
        while (stack.isNotEmpty() && count < maxEntries) {
            val (doc, parts) = stack.removeLast()
            for (child in doc.listFiles()) {
                val name = child.name ?: continue
                val childParts = parts + name
                val childPath = childParts.joinToString("/")
                val isDir = child.isDirectory
                yield(Entry(name, childPath, isDir, if (isDir) 0 else child.length()))
                count++
                if (isDir) stack.addLast(child to childParts)
                if (count >= maxEntries) break
            }
        }
    }

    // SAF's createFile appends the MIME's canonical extension when it doesn't
    // match the supplied one (so "README.md" + "text/plain" becomes
    // "README.md.txt"). Picking a MIME that matches the filename's extension
    // keeps the name intact on every Android provider.
    private fun mimeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun sanitize(path: String): List<String>? {
        val normalized = path.trim().trim('/')
        if (normalized.isEmpty()) return emptyList()
        val parts = normalized.split('/')
        for (p in parts) if (p.isEmpty() || p == "." || p == "..") return null
        return parts
    }
}
