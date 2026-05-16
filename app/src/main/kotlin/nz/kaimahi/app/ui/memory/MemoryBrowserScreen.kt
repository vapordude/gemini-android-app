package nz.kaimahi.app.ui.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.kaimahi.ui.KaimahiCaption
import nz.kaimahi.ui.KaimahiLogo
import nz.kaimahi.ui.KaimahiLogoStyle
import nz.kaimahi.ui.LocalKaimahiColors

enum class MemoryTone { Person, Tool, Knowledge, Muted }

data class MemoryEntry(
    val id: String,
    val label: String,
    val tag: String,
    val whenLabel: String,
    val tone: MemoryTone,
)

/**
 * Memory browser — Frame 5 placeholder. The locked design shows a DAG
 * laid out top-down with curved edges. Drawing a real graph layout is
 * non-trivial; this v1 ships the chrome (filter pills, legend, list of
 * memory cards in DAG order) and leaves the canvas-based visualisation
 * as a follow-up. Caller passes in the entries; data binding to the
 * actual MemoryStore comes next.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MemoryBrowserScreen(
    entries: List<MemoryEntry>,
    totalCount: Int,
    onDrawerOpen: () -> Unit,
    onSearch: () -> Unit,
    onEntryClick: (MemoryEntry) -> Unit,
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
                            "Memory browser",
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
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
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
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { FilterChip("All", count = totalCount, active = true) }
                item { FilterChip("Recent") }
                item { FilterChip("By tag") }
                item { FilterChip("Expiring") }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LegendDot("PERSON", tokens.brand)
                LegendDot("TOOL/TRACE", tokens.act)
                LegendDot("KNOWLEDGE", tokens.signal)
            }
            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(14.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(12.dp),
                ) {
                    EmptyMemoryHint()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.surface,
                            RoundedCornerShape(14.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(14.dp),
                        ),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        MemoryCard(entry, onClick = { onEntryClick(entry) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, count: Int? = null, active: Boolean = false) {
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
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        KaimahiCaption(
            text = label.uppercase(),
            fontSize = 11.sp,
            letterSpacing = 0.4.sp,
            color = if (active) tokens.textStrong else tokens.muted,
        )
        if (count != null) {
            KaimahiCaption(text = "· $count", fontSize = 11.sp, letterSpacing = 0.sp)
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(7.dp).background(color, CircleShape))
        KaimahiCaption(label, fontSize = 10.sp, letterSpacing = 0.6.sp)
    }
}

@Composable
private fun MemoryCard(entry: MemoryEntry, onClick: () -> Unit) {
    val tokens = LocalKaimahiColors.current
    val stripe = when (entry.tone) {
        MemoryTone.Person -> tokens.brand
        MemoryTone.Tool -> tokens.act
        MemoryTone.Knowledge -> tokens.signal
        MemoryTone.Muted -> tokens.muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .border(
                width = 1.dp,
                color = stripe.copy(alpha = 0.55f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(48.dp)
                .background(stripe),
        )
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                entry.label,
                fontSize = 11.5f.sp,
                color = tokens.textStrong,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KaimahiCaption(
                    text = entry.tag.uppercase(),
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    color = stripe,
                )
                KaimahiCaption(
                    text = entry.whenLabel,
                    fontSize = 9.sp,
                    letterSpacing = 0.6.sp,
                )
            }
        }
    }
}

@Composable
private fun EmptyMemoryHint() {
    val tokens = LocalKaimahiColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 30.dp, horizontal = 14.dp),
    ) {
        Text(
            "No memories yet.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Memories collect as you work. They form a DAG with edges (\"caused by\", \"refines\") and recall by recency.",
            fontSize = 12.sp,
            color = tokens.muted,
        )
    }
}

