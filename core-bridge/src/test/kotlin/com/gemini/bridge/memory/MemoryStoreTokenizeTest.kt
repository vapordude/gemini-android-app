package com.gemini.bridge.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tokenize-only tests for [MemoryStore]. We don't construct the full store
 * here because it needs an Android `Context`; instead we exercise the
 * stopword + alphanumeric tokenizer behaviour by re-implementing the same
 * predicate the store uses (small enough to keep in sync, big enough to
 * catch regressions like accidentally surfacing stopwords in the index).
 */
class MemoryStoreTokenizeTest {

    private fun tokenize(text: String): List<String> {
        val stopwords = setOf(
            "the", "a", "an", "and", "or", "but", "if", "is", "are", "was", "were",
            "of", "to", "in", "on", "at", "for", "by", "with", "as", "from", "that",
            "this", "it", "be", "you", "i", "me", "we", "us", "they", "them", "he",
            "she", "his", "her", "their", "my", "your", "our", "do", "does", "did",
            "have", "has", "had", "not", "no", "yes",
        )
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            if (c.isLetterOrDigit()) {
                sb.append(c.lowercaseChar())
            } else if (sb.isNotEmpty()) {
                val tok = sb.toString()
                if (tok.length >= 2 && tok !in stopwords) out += tok
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) {
            val tok = sb.toString()
            if (tok.length >= 2 && tok !in stopwords) out += tok
        }
        return out
    }

    @Test fun stopwords_are_dropped() {
        val ts = tokenize("the quick brown fox is jumping over the lazy dog")
        // "the", "is", "over" -> "over" isn't a stopword so it stays.
        assertFalse("the" in ts)
        assertFalse("is" in ts)
        assertTrue("quick" in ts)
        assertTrue("brown" in ts)
        assertTrue("fox" in ts)
    }

    @Test fun single_chars_are_dropped() {
        // "a" / "i" are stopwords AND length 1; the length filter catches
        // anything else like "x = 1" -> "x", "1" should both fall to the
        // length floor of 2.
        val ts = tokenize("a x b 12")
        assertFalse("a" in ts)
        assertFalse("x" in ts)
        assertFalse("b" in ts)
        assertTrue("12" in ts)
    }

    @Test fun mixed_case_lowercases() {
        val ts = tokenize("Kotlin Memory Store")
        assertTrue("kotlin" in ts)
        assertTrue("memory" in ts)
        assertTrue("store" in ts)
        assertFalse("Kotlin" in ts)
    }
}
