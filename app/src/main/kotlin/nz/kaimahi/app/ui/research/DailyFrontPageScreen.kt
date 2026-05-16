package nz.kaimahi.app.ui.research

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.kaimahi.bridge.research.FeedTopic
import nz.kaimahi.ui.KaimahiCaption
import nz.kaimahi.ui.KaimahiLogo
import nz.kaimahi.ui.KaimahiLogoStyle
import nz.kaimahi.ui.LocalKaimahiColors

data class FrontPageItem(
    val id: String,
    val title: String,
    val sourceDisplayName: String,
    val sourceTopicLabel: String,
    val whenLabel: String,
    val starred: Boolean = false,
    val matchedKeywords: List<String> = emptyList(),
)

/**
 * Daily front page — research feed of arXiv drops, lab blogs, and the
 * synthesis territory. v1 surface; the real fetcher lands next. See
 * `docs/research/daily-front-page.md` for the source list this screen
 * binds to.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DailyFrontPageScreen(
    dateLine: String,
    summaryLine: String,
    items: List<FrontPageItem>,
    activeTopic: FeedTopic,
    availableTopics: List<FeedTopic>,
    onDrawerOpen: () -> Unit,
    onRefresh: () -> Unit,
    onTopicSelected: (FeedTopic) -> Unit,
    onItemClick: (FrontPageItem) -> Unit,
    onItemStarToggle: (FrontPageItem) -> Unit,
) {
    val tokens = LocalKaimahiColors.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        KaimahiLogo(size = 22.dp, style = KaimahiLogoStyle.Solid)
                        Text(
                            "Daily front page",
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Medium,
                            fontSize = 19.sp,
                            color = tokens.textStrong,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onDrawerOpen) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
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
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 8.dp),
            ) {
                Text(
                    text = dateLine,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.Medium,
                    fontSize = 26.sp,
                    color = tokens.textStrong,
                )
                Spacer(Modifier.height(6.dp))
                KaimahiCaption(
                    text = summaryLine.uppercase(),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                )
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(availableTopics) { topic ->
                    TopicChip(
                        topic = topic,
                        active = topic == activeTopic,
                        onClick = { onTopicSelected(topic) },
                    )
                }
            }

            if (items.isEmpty()) {
                EmptyFeedHint()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { entry ->
                        FrontPageCard(
                            entry = entry,
                            onClick = { onItemClick(entry) },
                            onStarToggle = { onItemStarToggle(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicChip(topic: FeedTopic, active: Boolean, onClick: () -> Unit) {
    val tokens = LocalKaimahiColors.current
    Row(
        modifier = Modifier
            .height(28.dp)
            .background(
                if (active) tokens.brand.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp),
            )
            .border(
                width = 1.dp,
                color = if (active) tokens.brand.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KaimahiCaption(
            text = topic.displayName.uppercase(),
            fontSize = 11.sp,
            letterSpacing = 0.4.sp,
            color = if (active) tokens.textStrong else tokens.muted,
        )
    }
}

@Composable
private fun FrontPageCard(
    entry: FrontPageItem,
    onClick: () -> Unit,
    onStarToggle: () -> Unit,
) {
    val tokens = LocalKaimahiColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = tokens.textStrong,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KaimahiCaption(
                    text = entry.sourceDisplayName,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                    color = tokens.act,
                )
                KaimahiCaption(text = "·", fontSize = 10.sp, letterSpacing = 0.sp)
                KaimahiCaption(
                    text = entry.sourceTopicLabel,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                )
                KaimahiCaption(text = "·", fontSize = 10.sp, letterSpacing = 0.sp)
                KaimahiCaption(
                    text = entry.whenLabel,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                )
            }
            if (entry.matchedKeywords.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = entry.matchedKeywords.joinToString(" · "),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5f.sp,
                    color = tokens.signal,
                )
            }
        }
        Text(
            text = if (entry.starred) "★" else "☆",
            fontSize = 18.sp,
            color = if (entry.starred) tokens.signal else tokens.muted,
            modifier = Modifier.clickable(onClick = onStarToggle),
        )
    }
}

@Composable
private fun EmptyFeedHint() {
    val tokens = LocalKaimahiColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 30.dp),
    ) {
        Text(
            "Feed fetcher coming soon.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildString {
                append("The source list is locked in ")
                append("`core-bridge/research/FeedSources.kt`. ")
                append("Next step: a Kotlin RSS fetcher pulls arXiv ")
                append("cs.LG / cs.AI / quant-ph / gr-qc / nlin.AO / q-bio.NC ")
                append("once on app foreground, caches under ")
                append("`filesDir/research/feed.json`, and renders here.")
            },
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = tokens.muted,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "See docs/research/daily-front-page.md for the full plan.",
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 12.5f.sp,
            color = tokens.muted,
        )
    }
}
