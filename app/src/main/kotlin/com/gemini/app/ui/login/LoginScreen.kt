package com.gemini.app.ui.login

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gemini.app.R
import com.gemini.ui.LocalGeminiColors
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.OpenInNew

private const val AISTUDIO_KEY_URL = "https://aistudio.google.com/app/apikey"

@Composable
fun LoginScreen(
    onLoginSuccess: (Map<String, Any>) -> Unit,
    isLoading: Boolean = false
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authService = remember { GoogleAuthService(context) }

    var apiKey by remember { mutableStateOf("") }
    var selectedTabIndex by remember { mutableStateOf(0) }
    var isGoogleLoading by remember { mutableStateOf(false) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val account = authService.parseSignInResult(result.data)
        if (account != null) {
            isGoogleLoading = true
            coroutineScope.launch {
                val token = authService.getAccessToken(account)
                isGoogleLoading = false
                if (token != null) {
                    onLoginSuccess(mapOf("access_token" to token))
                }
            }
        }
    }

    val dusk = LocalGeminiColors.current.duskGradient
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
            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(96.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Image(
                painter = painterResource(id = R.drawable.logo_gemini),
                contentDescription = "Gemini",
                modifier = Modifier.height(48.dp)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Native Android client for the Gemini API",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(28.dp))

            TabRow(selectedTabIndex = selectedTabIndex, modifier = Modifier.fillMaxWidth()) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text(stringResource(R.string.login_tab_api)) }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text(stringResource(R.string.login_tab_google)) }
                )
            }

            Spacer(Modifier.height(16.dp))

            if (selectedTabIndex == 0) {
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
                        Text("Step 1 — Get a key", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Open Google AI Studio (signs you in with your Google account if needed), " +
                                "tap \"Create API key\", and copy it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { openUrl(context, AISTUDIO_KEY_URL) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open Google AI Studio")
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

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
                        Text("Step 2 — Paste it here", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Kept only in memory for this session. You can change it later from Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text(stringResource(R.string.login_api_key_label)) },
                                placeholder = { Text("AIza…") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = {
                                val pasted = readClipboard(context)
                                if (!pasted.isNullOrBlank()) apiKey = pasted.trim()
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        if (isLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Contacting Gemini…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Button(
                                onClick = {
                                    if (apiKey.isNotBlank()) onLoginSuccess(mapOf("api_key" to apiKey.trim()))
                                },
                                enabled = apiKey.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Key, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.login_api_continue))
                            }
                        }
                    }
                }
            } else {
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
                        Text("Sign in with Google", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Use your Google Account to authenticate directly. This requires access to your cloud platform and generative language API scopes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))

                        if (isLoading || isGoogleLoading) {
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
                                Text(stringResource(R.string.login_sign_in_google))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = cm.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context).toString()
}
