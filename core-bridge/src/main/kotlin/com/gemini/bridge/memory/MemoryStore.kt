package com.gemini.bridge.memory

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Two-layer long-term memory.
 *
 *   • Facts: short structured key/value pairs (e.g. "name" -> "Dan",
 *     "prefers" -> "Rust over Go"). Always injected into the model's system
 *     prompt verbatim. Append-only JSONL with last-write-wins per key.
 *   • Notes: free-form markdown, indexed by lowercased keyword TF-IDF.
 *     Surfaced only when the model calls RecallMemoryTool, keeping the
 *     system prompt cheap.
 *
 * Both stores live under app.filesDir/memory/ and are app-private. No
 * embedding model, no database — pure file I/O so the store works before
 * the Rust driver lands.
 */
class MemoryStore(context: Context) {

    private val root: File = File(context.filesDir, "memory").apply { mkdirs() }
    private val factsFile: File = File(root, "facts.jsonl")
    private val notesDir: File = File(root, "notes").apply { mkdirs() }
    private val indexFile: File = File(root, "notes.idx")

    // ---- Facts ----

    /** Read the live fact set (last-write-wins per key). */
    @Synchronized
    fun listFacts(): Map<String, String> {
        if (!factsFile.exists()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        factsFile.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                runCatching {
                    val obj = JSONObject(line)
                    val op = obj.optString("op")
                    val key = obj.optString("key")
                    when (op) {
                        "set" -> out[key] = obj.optString("value")
                        "del" -> out.remove(key)
                    }
                }
            }
        }
        return out
    }

    @Synchronized
    fun setFact(key: String, value: String) {
        if (key.isBlank()) return
        val rec = JSONObject().apply {
            put("op", "set")
            put("key", key.trim())
            put("value", value)
            put("ts", System.currentTimeMillis())
        }
        factsFile.appendText(rec.toString() + "\n")
    }

    @Synchronized
    fun deleteFact(key: String) {
        if (key.isBlank()) return
        val rec = JSONObject().apply {
            put("op", "del")
            put("key", key.trim())
            put("ts", System.currentTimeMillis())
        }
        factsFile.appendText(rec.toString() + "\n")
    }

    /**
     * Render facts as a system-prompt block. Empty when no facts are set.
     * Intended for inclusion in `buildSystemInstruction` in `RestGeminiCore`.
     */
    fun factsForSystemPrompt(): String {
        val facts = listFacts()
        if (facts.isEmpty()) return ""
        return buildString {
            append("Persistent facts about the user (from memory store):\n")
            for ((k, v) in facts) {
                append("- ").append(k).append(": ").append(v).append('\n')
            }
        }
    }

    // ---- Notes ----

    @Synchronized
    fun writeNote(title: String, content: String): String {
        val id = noteId(title)
        val file = File(notesDir, "$id.md")
        val header = "# $title\n\n"
        file.writeText(header + content)
        rebuildIndex()
        return id
    }

    @Synchronized
    fun readNote(id: String): String? = File(notesDir, "$id.md").takeIf { it.exists() }?.readText()

    @Synchronized
    fun listNotes(): List<NoteEntry> = (notesDir.listFiles { f -> f.extension == "md" } ?: emptyArray())
        .map { NoteEntry(id = it.nameWithoutExtension, title = firstHeading(it), bytes = it.length()) }
        .sortedBy { it.title }

    @Synchronized
    fun deleteNote(id: String): Boolean {
        val ok = File(notesDir, "$id.md").delete()
        if (ok) rebuildIndex()
        return ok
    }

    /**
     * TF-IDF-style retrieval: tokenize [query], score each note by the sum of
     * (term_freq_in_doc) * log(N / df(term)), return top-[k]. Lightweight
     * enough to run on the main thread for under ~1000 notes; for more, push
     * the call to Dispatchers.IO at the call site.
     */
    @Synchronized
    fun recall(query: String, k: Int = 5): List<RecallHit> {
        val terms = tokenize(query)
        if (terms.isEmpty()) return emptyList()
        val idx = readIndex()
        if (idx.docCount == 0) return emptyList()
        val n = idx.docCount.toDouble()

        val scored = mutableListOf<RecallHit>()
        for ((docId, docTermFreqs) in idx.byDoc) {
            var score = 0.0
            for (t in terms) {
                val tf = docTermFreqs[t] ?: continue
                val df = idx.docFrequency[t] ?: continue
                if (df == 0) continue
                score += tf * Math.log(n / df)
            }
            if (score > 0) {
                val file = File(notesDir, "$docId.md")
                if (file.exists()) {
                    val snippet = file.readText().take(SNIPPET_CHARS)
                    scored += RecallHit(docId, firstHeading(file), score, snippet)
                }
            }
        }
        return scored.sortedByDescending { it.score }.take(k)
    }

    private fun firstHeading(f: File): String =
        f.bufferedReader().useLines { lines -> lines.firstOrNull()?.removePrefix("#")?.trim().orEmpty() }
            .ifBlank { f.nameWithoutExtension }

    private fun noteId(title: String): String {
        val raw = title.trim().ifBlank { "untitled-${System.currentTimeMillis()}" }
        val md = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).take(8)
        return md.joinToString("") { "%02x".format(it) }
    }

    // ---- Index ----

    /**
     * Persisted format (newline-delimited JSON):
     *   line 0: {"v":1,"N":<docCount>,"df":{"term":n,...}}
     *   line k: {"id":"<docId>","tf":{"term":n,...}}
     */
    private data class IndexView(
        val docCount: Int,
        val docFrequency: Map<String, Int>,
        val byDoc: Map<String, Map<String, Int>>,
    )

    private fun readIndex(): IndexView {
        if (!indexFile.exists()) return IndexView(0, emptyMap(), emptyMap())
        val lines = indexFile.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return IndexView(0, emptyMap(), emptyMap())
        val header = runCatching { JSONObject(lines[0]) }.getOrNull()
            ?: return IndexView(0, emptyMap(), emptyMap())
        val n = header.optInt("N", 0)
        val df = mutableMapOf<String, Int>()
        header.optJSONObject("df")?.let { obj ->
            for (key in obj.keys()) df[key] = obj.optInt(key, 0)
        }
        val byDoc = mutableMapOf<String, Map<String, Int>>()
        for (i in 1 until lines.size) {
            val obj = runCatching { JSONObject(lines[i]) }.getOrNull() ?: continue
            val id = obj.optString("id").ifBlank { continue }
            val tfMap = mutableMapOf<String, Int>()
            obj.optJSONObject("tf")?.let { tfObj ->
                for (key in tfObj.keys()) tfMap[key] = tfObj.optInt(key, 0)
            }
            byDoc[id] = tfMap
        }
        return IndexView(n, df, byDoc)
    }

    private fun rebuildIndex() {
        val docs = notesDir.listFiles { f -> f.extension == "md" } ?: emptyArray()
        val df = mutableMapOf<String, Int>()
        val byDoc = mutableMapOf<String, Map<String, Int>>()
        for (f in docs) {
            val tf = mutableMapOf<String, Int>()
            for (token in tokenize(f.readText())) tf[token] = (tf[token] ?: 0) + 1
            byDoc[f.nameWithoutExtension] = tf
            for (t in tf.keys) df[t] = (df[t] ?: 0) + 1
        }
        val header = JSONObject().apply {
            put("v", 1)
            put("N", docs.size)
            put("df", JSONObject(df.mapValues { it.value }))
        }
        val sb = StringBuilder().append(header).append('\n')
        for ((id, tf) in byDoc) {
            val rec = JSONObject().apply {
                put("id", id)
                put("tf", JSONObject(tf.mapValues { it.value }))
            }
            sb.append(rec).append('\n')
        }
        indexFile.writeText(sb.toString())
    }

    private fun tokenize(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit()) {
                sb.append(c.lowercaseChar())
            } else if (sb.isNotEmpty()) {
                val tok = sb.toString()
                if (tok.length >= 2 && tok !in STOPWORDS) out += tok
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) {
            val tok = sb.toString()
            if (tok.length >= 2 && tok !in STOPWORDS) out += tok
        }
        return out
    }

    private companion object {
        const val SNIPPET_CHARS = 300
        val STOPWORDS = setOf(
            "the", "a", "an", "and", "or", "but", "if", "is", "are", "was", "were",
            "of", "to", "in", "on", "at", "for", "by", "with", "as", "from", "that",
            "this", "it", "be", "you", "i", "me", "we", "us", "they", "them", "he",
            "she", "his", "her", "their", "my", "your", "our", "do", "does", "did",
            "have", "has", "had", "not", "no", "yes",
        )
    }
}

data class NoteEntry(val id: String, val title: String, val bytes: Long)
data class RecallHit(val id: String, val title: String, val score: Double, val snippet: String)
