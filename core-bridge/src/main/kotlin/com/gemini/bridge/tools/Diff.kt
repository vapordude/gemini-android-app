package com.gemini.bridge.tools

/**
 * Minimal unified-diff generator used for the write/edit tool output. Good
 * enough to render inline in the chat — not a production diff library.
 */
internal object Diff {

    const val HEADER_MARKER = "@@DIFF@@"

    fun of(before: String, after: String, path: String, maxLines: Int = 400): String {
        val a = before.lines()
        val b = after.lines()
        val n = a.size
        val m = b.size
        // LCS table
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val out = StringBuilder()
        out.append(HEADER_MARKER).append(' ').append(path).append('\n')
        var i = 0; var j = 0
        var plus = 0; var minus = 0; var emitted = 0
        while (i < n && j < m && emitted < maxLines) {
            when {
                a[i] == b[j] -> {
                    out.append(' ').append(a[i]).append('\n'); i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    out.append('-').append(a[i]).append('\n'); i++; minus++
                }
                else -> {
                    out.append('+').append(b[j]).append('\n'); j++; plus++
                }
            }
            emitted++
        }
        while (i < n && emitted < maxLines) {
            out.append('-').append(a[i]).append('\n'); i++; minus++; emitted++
        }
        while (j < m && emitted < maxLines) {
            out.append('+').append(b[j]).append('\n'); j++; plus++; emitted++
        }
        if (emitted >= maxLines && (i < n || j < m)) {
            out.append("… diff truncated\n")
        }
        return "+$plus / -$minus — $path\n$out"
    }
}
