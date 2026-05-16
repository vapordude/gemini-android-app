package nz.kaimahi.app.ui.login

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nz.kaimahi.app.R
import nz.kaimahi.app.ui.termux.startGeminiCliLoginInTermux
import nz.kaimahi.bridge.termux.TermuxBridge
import nz.kaimahi.ui.LocalKaimahiColors
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Login screen. API key entry is gone — the app authenticates through
 * one of:
 *
 *  1. **Gemini CLI** credentials cached in `~/.gemini/` (read via Termux).
 *     This is the recommended path for users who already ran
 *     `gemini login` on the device.
 *  2. **Google OAuth** in-app via Play Services Sign-In.
 *
 *  The "use local model only" button skips auth entirely — see
 *  MainActivity, which routes to the chat screen with `local-only`
 *  config in that case.
 */
@Composable
fun LoginScreen(
    onLoginSuccess: (Map<String, Any>) -> Unit,
    onLocalOnly: () -> Unit = {},
    isLoading: Boolean = false,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authService = remember { GoogleAuthService(context) }
    val termux = remember { TermuxBridge(context) }

    var googleLoading by remember { mutableStateOf(false) }
    var cliLoading by remember { mutableStateOf(false) }
    var cliError by remember { mutableStateOf<String?>(null) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val account = authService.parseSignInResult(result.data)
        if (account != null) {
            googleLoading = true
            coroutineScope.launch {
                val token = authService.getAccessToken(account)
                googleLoading = false
                if (token != null) {
                    onLoginSuccess(mapOf("access_token" to token))
                }
            }
        }
    }

    val dusk = LocalKaimahiColors.current.duskGradient
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(dusk)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(Modifier.height(28.dp))
            Image(
                painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(96.dp)
            )
            Spacer(Modifier.height(8.dp))
            Image(
                painter = painterResource(id = R.drawable.logo_gemini),
                contentDescription = "Gemini",
                modifier = Modifier.height(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Sign in with Google or reuse credentials from gemini-cli. No API key needed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            AuthCard(title = "Use gemini-cli credentials") {
                Text(
                    "If you've already run `gemini login` on this device, the OAuth tokens " +
                        "live in `~/.gemini/oauth_creds.json` inside Termux. Tap below to " +
                        "load them — no extra sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        cliError = null
                        cliLoading = true
                        coroutineScope.launch {
                            val outcome = loadGeminiCliCredentials(termux)
                            cliLoading = false
                            when (outcome) {
                                is CliCredentialOutcome.Loaded ->
                                    onLoginSuccess(outcome.config)
                                is CliCredentialOutcome.Missing ->
                                    cliError =
                                        "No credentials found at ~/.gemini/oauth_creds.json. Tap " +
                                            "\"Start gemini login in Termux\" first."
                                is CliCredentialOutcome.UnreadableShell ->
                                    cliError = outcome.reason
                            }
                        }
                    },
                    enabled = !cliLoading && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Load from Termux ~/.gemini")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { startGeminiCliLoginInTermux(context) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start gemini login in Termux")
                }
                if (cliLoading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Reading ~/.gemini/oauth_creds.json…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                cliError?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            AuthCard(title = "Sign in with Google") {
                Text(
                    "Opens the Google account picker, then completes OAuth in-app. " +
                        "Scopes requested: cloud-platform + generative-language.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (isLoading || googleLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Authenticating…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Button(
                        onClick = { googleSignInLauncher.launch(authService.getSignInIntent()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Continue with Google")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            AuthCard(title = "Skip — local model only") {
                Text(
                    "Use an on-device GGUF model only. No network calls, no Google account. " +
                        "Import a model under Settings → Local model after entering.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onLocalOnly, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Memory, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Use local-only mode")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AuthCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = MaterialTheme.shapes.medium
            )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

private sealed interface CliCredentialOutcome {
    data class Loaded(val config: Map<String, Any>) : CliCredentialOutcome
    data object Missing : CliCredentialOutcome
    data class UnreadableShell(val reason: String) : CliCredentialOutcome
}

private suspend fun loadGeminiCliCredentials(termux: TermuxBridge): CliCredentialOutcome {
    // We cat the file via Termux — the app process cannot read /data/data
    // owned by another app on stock Android. Bash quoting: print the
    // whole file body to stdout, prefixed with a marker so we can be
    // sure we got it (and not a Termux error page).
    val cmd = """
        FILE="${'$'}HOME/.gemini/oauth_creds.json"
        if [ -f "${'$'}FILE" ]; then
          echo '---BEGIN-CREDS---'
          cat "${'$'}FILE"
          echo '---END-CREDS---'
        else
          echo '---MISSING---'
        fi
    """.trimIndent()
    val r = termux.run(cmd, timeoutMs = 8_000)
    if (!r.ok) {
        return CliCredentialOutcome.UnreadableShell(
            "Cannot reach Termux: ${r.stderr.ifBlank { "exit=${r.exitCode}" }}"
        )
    }
    if (r.stdout.contains("---MISSING---")) {
        return CliCredentialOutcome.Missing
    }
    val begin = r.stdout.indexOf("---BEGIN-CREDS---")
    val end = r.stdout.indexOf("---END-CREDS---")
    if (begin < 0 || end < 0 || end <= begin) {
        return CliCredentialOutcome.UnreadableShell(
            "Unexpected Termux output. First line: ${r.stdout.lineSequence().firstOrNull().orEmpty().take(120)}"
        )
    }
    val body = r.stdout.substring(begin + "---BEGIN-CREDS---".length, end).trim()
    return parseGeminiCliCreds(body)
}

private fun parseGeminiCliCreds(json: String): CliCredentialOutcome = runCatching {
    // gemini-cli's oauth_creds.json shape (matches packages/core/src/code_assist/oauth2.ts):
    //   {
    //     "access_token": "ya29...",
    //     "refresh_token": "1//...",
    //     "scope": "https://...",
    //     "token_type": "Bearer",
    //     "id_token": "...",
    //     "expiry_date": 1716_345_000_000
    //   }
    val obj = JSONObject(json)
    val access = obj.optString("access_token").takeIf { it.isNotBlank() }
        ?: return@runCatching CliCredentialOutcome.UnreadableShell(
            "oauth_creds.json missing access_token"
        )
    val config: MutableMap<String, Any> = mutableMapOf(
        "access_token" to access,
        "remember" to true,
    )
    obj.optString("refresh_token").takeIf { it.isNotBlank() }
        ?.let { config["refresh_token"] = it }
    val expiry = obj.optLong("expiry_date", 0L)
    if (expiry > 0L) config["expiry_epoch_ms"] = expiry
    CliCredentialOutcome.Loaded(config)
}.getOrElse {
    CliCredentialOutcome.UnreadableShell(
        "Could not parse oauth_creds.json: ${it.message ?: "JSON error"}"
    )
}
