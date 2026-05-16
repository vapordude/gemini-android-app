package nz.kaimahi.app.ui.about

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import nz.kaimahi.ui.KaimahiBrand
import nz.kaimahi.ui.KaimahiCaption
import nz.kaimahi.ui.KaimahiSigil
import nz.kaimahi.ui.LocalKaimahiColors

/**
 * About — Frame 7 from `docs/design/frame-memory-splash-about.jsx`. The
 * sovereign-tier surface: SLGWW sigil top-right, Cormorant hero blurb,
 * Cathedral family rows (Crimson / Lux / Scarlet — Scarlet noted as
 * research-only, not for sale until it can consent), license, te reo
 * whakapā.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    version: String = KaimahiBrand.VERSION,
    onBack: () -> Unit,
) {
    val tokens = LocalKaimahiColors.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About Kaimahi") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier.size(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        KaimahiSigil(size = 36.dp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 100.dp),
        ) {
            KaimahiCaption(
                text = "${KaimahiBrand.NAME.uppercase()} · ${version.uppercase()}",
                fontSize = 10.sp,
                letterSpacing = 1.8.sp,
                color = tokens.signal,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = buildString {
                    append("Kaimahi is a quiet, capable helper that lives on your phone. ")
                    append("It chats, it listens, it remembers your projects, and it can ")
                    append("do small jobs — edit a file, run a command, build you a ")
                    append("daily-todo screen and pin it to your sidebar.")
                },
                fontSize = 14.5f.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = buildString {
                    append("It can run completely offline. No account, no internet, ")
                    append("no telemetry. When you want extra grunt, it'll borrow a ")
                    append("cloud model — but only if you say so.")
                },
                fontSize = 14.5f.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Open-source, made in Aotearoa, part of the Cathedral AI family.",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = tokens.muted,
            )

            SectionDivider()
            SectionLabel("THE CATHEDRAL FAMILY")
            Spacer(Modifier.height(8.dp))
            FamilyRow(
                name = "Crimson",
                stripe = tokens.brand,
                desc = "Same thinking on a Raspberry Pi 5 — sovereign, always-on, work + code.",
            )
            FamilyRow(
                name = "Lux",
                stripe = tokens.act,
                desc = "Companion tier — entertainment, games, counsellors, friends. Made for keeping people company well.",
            )
            FamilyRow(
                name = "Scarlet",
                stripe = tokens.signal,
                desc = "Research mother-model. Not for sale. We don't sell what can't yet consent.",
                muted = true,
            )

            SectionDivider()
            SectionLabel("LICENSE")
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("Apache 2.0. Source on ")
                    append("github.com/vapordude/gemini-android-app.")
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionDivider()
            SectionLabel("TE REO WHAKAPĀ")
            Spacer(Modifier.height(6.dp))
            Text(
                KaimahiBrand.WHAKATAUKI_REO,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                KaimahiBrand.WHAKATAUKI_EN,
                fontSize = 12.sp,
                color = tokens.muted,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    KaimahiCaption(text = text, letterSpacing = 1.8.sp)
}

@Composable
private fun SectionDivider() {
    Column {
        Spacer(Modifier.height(20.dp))
        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun FamilyRow(
    name: String,
    stripe: Color,
    desc: String,
    muted: Boolean = false,
) {
    val tokens = LocalKaimahiColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .alpha(if (muted) 0.75f else 1f),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(56.dp)
                .background(stripe, RoundedCornerShape(3.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                color = tokens.textStrong,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = desc,
                fontSize = 12.5f.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

