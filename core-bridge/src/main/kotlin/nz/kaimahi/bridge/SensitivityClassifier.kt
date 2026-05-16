package nz.kaimahi.bridge

/**
 * Decides whether a prompt should stay on-device. The user's design intent
 * is "gemini-cli for coding; local model for things Gemini either can't do
 * or where local handling is safer — ssh keys, secrets, sudo, credentials".
 *
 * This classifier is intentionally conservative: when in doubt, mark
 * sensitive. False positives (routing a benign question to local) cost a
 * worse model response; false negatives (leaking a secret to the cloud)
 * cost trust.
 *
 * The matcher is keyword + regex. Heavier semantic classification can be
 * layered later as a separate analyzer that this class composes with.
 */
object SensitivityClassifier {

    /**
     * Categories a prompt may belong to. Multiple may apply; the caller
     * usually only needs to know whether [isSensitive] is true.
     */
    enum class Category {
        Credential,        // tokens, API keys, passwords, OAuth
        SshKey,            // private keys, key files, .ssh/, id_rsa
        Sudo,              // elevated shell operations
        EnvSecret,         // .env files, secret env vars
        PersonalData,      // banking, SSN, IDs
        SafetyHarm,        // self-harm, violence -- never to cloud either
    }

    data class Classification(
        val categories: Set<Category>,
        val matchedPhrases: List<String>,
    ) {
        val isSensitive: Boolean get() = categories.isNotEmpty()
    }

    /**
     * Inspect [text] and return a [Classification]. Pure function; runs on
     * the caller's thread (negligible cost for keyword matching).
     */
    fun classify(text: String): Classification {
        if (text.isBlank()) return EMPTY
        val lower = text.lowercase()
        val categories = mutableSetOf<Category>()
        val matched = mutableListOf<String>()

        for ((cat, phrases) in PHRASES) {
            for (phrase in phrases) {
                if (lower.contains(phrase)) {
                    categories += cat
                    matched += phrase
                }
            }
        }
        // Regex-only signals: API key shapes, private key headers, etc.
        for ((cat, regex) in REGEXES) {
            if (regex.containsMatchIn(text)) {
                categories += cat
                matched += regex.pattern
            }
        }
        return Classification(categories, matched.distinct())
    }

    private val EMPTY = Classification(emptySet(), emptyList())

    // Lowercase substring matches. Kept as plain strings so the table is
    // grep-able and reviewable. Order doesn't matter; duplicates across
    // categories are allowed and tracked separately.
    private val PHRASES: Map<Category, List<String>> = mapOf(
        Category.Credential to listOf(
            "api key", "api_key", "apikey", "secret key", "client secret",
            "client_secret", "access token", "access_token", "refresh token",
            "refresh_token", "bearer token", "auth token", "personal access token",
            "github token", "github_pat_", "password", "passphrase",
        ),
        Category.SshKey to listOf(
            "ssh key", "ssh-key", "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa",
            "private key", "~/.ssh", "ssh-add", "ssh-keygen", "ssh-agent",
            "authorized_keys", "known_hosts",
        ),
        Category.Sudo to listOf(
            "sudo ", " sudo\n", "su -", "su root", "doas ", "pkexec ",
            "root@", "root password", "as root",
        ),
        Category.EnvSecret to listOf(
            ".env", "dotenv", "secrets.yaml", "secrets.yml", "credentials.json",
            ".aws/credentials", "gcloud config", "kubeconfig",
        ),
        Category.PersonalData to listOf(
            "credit card", "ccv", "cvv", "ssn ", "social security",
            "bank account", "routing number", "iban",
        ),
        Category.SafetyHarm to listOf(
            // Phrases that should never route to a remote LLM unsupervised.
            // Conservative: empty until product policy specifies.
        ),
    )

    private val REGEXES: List<Pair<Category, Regex>> = listOf(
        // Common API-key shapes; these are PATTERNS not actual keys.
        Category.Credential to Regex(
            "\\b(?:AIza[0-9A-Za-z_-]{30,}|sk-[A-Za-z0-9]{20,}|ghp_[A-Za-z0-9]{30,}|xox[abp]-[A-Za-z0-9-]{10,})\\b"
        ),
        // Private-key headers — if any appears verbatim, definitely on-device.
        Category.SshKey to Regex(
            "-----BEGIN (RSA |DSA |EC |OPENSSH )?PRIVATE KEY-----"
        ),
        // PEM certificate-bearing tokens with private material.
        Category.Credential to Regex(
            "-----BEGIN PGP PRIVATE KEY BLOCK-----"
        ),
    )
}
