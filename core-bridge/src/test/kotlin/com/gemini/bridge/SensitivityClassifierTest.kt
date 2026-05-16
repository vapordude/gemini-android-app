package nz.kaimahi.bridge

import nz.kaimahi.bridge.SensitivityClassifier.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitivityClassifierTest {

    @Test fun blank_input_is_not_sensitive() {
        assertFalse(SensitivityClassifier.classify("").isSensitive)
        assertFalse(SensitivityClassifier.classify("   ").isSensitive)
    }

    @Test fun ordinary_coding_question_is_not_sensitive() {
        val r = SensitivityClassifier.classify(
            "Refactor this Kotlin file to extract the network layer into a separate class."
        )
        assertFalse("matched: ${r.matchedPhrases}", r.isSensitive)
    }

    @Test fun ssh_key_mention_routes_local() {
        val r = SensitivityClassifier.classify("Can you help me debug my id_rsa file?")
        assertTrue(r.isSensitive)
        assertTrue(Category.SshKey in r.categories)
    }

    @Test fun pem_private_key_header_is_caught() {
        val r = SensitivityClassifier.classify(
            "Here's the key:\n-----BEGIN OPENSSH PRIVATE KEY-----\nblahblah"
        )
        assertTrue(r.isSensitive)
        assertTrue(Category.SshKey in r.categories)
    }

    @Test fun api_key_pattern_is_caught() {
        // Synthetic key that matches the AIza... shape (not a real key).
        val r = SensitivityClassifier.classify("My key is AIzaSyAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(r.isSensitive)
        assertTrue(Category.Credential in r.categories)
    }

    @Test fun sudo_command_is_caught() {
        val r = SensitivityClassifier.classify("Run sudo apt update to refresh package indexes")
        assertTrue(r.isSensitive)
        assertTrue(Category.Sudo in r.categories)
    }

    @Test fun env_file_path_is_caught() {
        val r = SensitivityClassifier.classify("Read my .env file and explain each variable")
        assertTrue(r.isSensitive)
        assertTrue(Category.EnvSecret in r.categories)
    }

    @Test fun multiple_categories_can_coexist() {
        val r = SensitivityClassifier.classify(
            "My ssh-key broke after I ran sudo chmod on ~/.ssh"
        )
        assertTrue(r.isSensitive)
        assertTrue(Category.SshKey in r.categories)
        assertTrue(Category.Sudo in r.categories)
    }

    @Test fun matched_phrases_are_listed_in_result() {
        val r = SensitivityClassifier.classify("rotate my access_token please")
        assertTrue("access_token" in r.matchedPhrases)
    }

    @Test fun bare_word_password_alone_is_sensitive() {
        // Conservative: any mention of password marks it. The downstream router
        // will fall back to remote if the user explicitly opts in via mode.
        val r = SensitivityClassifier.classify("forgot my password")
        assertTrue(r.isSensitive)
        assertTrue(Category.Credential in r.categories)
    }

    @Test fun phrase_matching_is_case_insensitive() {
        val r = SensitivityClassifier.classify("SUDO REINSTALL")
        assertTrue(r.isSensitive)
        assertEquals(setOf(Category.Sudo), r.categories)
    }
}
