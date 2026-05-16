package nz.kaimahi.app.ui.dynamic

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.kaimahi.ui.KaimahiLogo
import nz.kaimahi.ui.KaimahiLogoStyle
import nz.kaimahi.ui.LocalKaimahiColors

data class TodoItem(
    val id: String,
    val text: String,
    val meta: String,
    val done: Boolean = false,
    val overdue: Boolean = false,
)

/**
 * Daily todo screen — the canonical example of "the agent built me a
 * screen". Matches Frame 3 in docs/design/frame-todo-chat.jsx.
 *
 * Stateful caller passes in the date strip text, the item list, and
 * handlers. The screen itself is presentation-only.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DailyTodoScreen(
    dateLine: String,
    summaryLine: String,
    items: List<TodoItem>,
    builtNote: String?,
    onDrawerOpen: () -> Unit,
    onOverflow: () -> Unit,
    onToggleDone: (TodoItem) -> Unit,
    onAdd: () -> Unit,
    onEditSchema: () -> Unit,
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
                            "Daily todo",
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
                    IconButton(onClick = onOverflow) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add", fontWeight = FontWeight.Medium) },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Date strip
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
                    Text(
                        text = summaryLine.uppercase(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        color = tokens.muted,
                    )
                }

                // List
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp, top = 10.dp, bottom = 90.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        TodoCard(item, onClick = { onToggleDone(item) })
                    }
                    if (items.isEmpty()) {
                        item { EmptyTodoHint() }
                    }
                }
            }

            // Bottom "Built by Kaimahi" card — overlaid above the FAB
            if (builtNote != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 92.dp),
                ) {
                    BuiltByCard(text = builtNote, onClick = onEditSchema)
                }
            }
        }
    }
}

@Composable
private fun TodoCard(item: TodoItem, onClick: () -> Unit) {
    val tokens = LocalKaimahiColors.current
    val nameColor = if (item.done) tokens.muted else tokens.textStrong
    val metaColor = when {
        item.overdue -> tokens.brand
        else -> tokens.muted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
    ) {
        // Whero left stripe on overdue (3dp wide, full card height)
        if (item.overdue) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(58.dp)
                    .background(tokens.brand),
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(
                        if (item.done) tokens.signal else Color.Transparent,
                        RoundedCornerShape(5.dp),
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (item.done) tokens.signal else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(5.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (item.done) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Done",
                        tint = Color(0xFF0A0A0A),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.text,
                    fontSize = 14.5f.sp,
                    color = nameColor,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.meta,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.5f.sp,
                    letterSpacing = 0.4.sp,
                    color = metaColor,
                )
            }
        }
    }
}

@Composable
private fun BuiltByCard(text: String, onClick: () -> Unit) {
    val tokens = LocalKaimahiColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = tokens.signal.copy(alpha = 0.35f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(tokens.signal.copy(alpha = 0.14f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            KaimahiLogo(size = 18.dp, style = KaimahiLogoStyle.Solid)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                fontSize = 12.5f.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 17.sp,
            )
            Text(
                "Tap to edit the schema.",
                fontSize = 11.5f.sp,
                color = tokens.muted,
            )
        }
        Text(
            "›",
            fontSize = 14.sp,
            color = tokens.muted,
        )
    }
}

@Composable
private fun EmptyTodoHint() {
    val tokens = LocalKaimahiColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "Nothing on the list.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Ask Kaimahi to add something:",
            fontSize = 13.sp,
            color = tokens.muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "\"Add 'buy bread' to my daily todo.\"",
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 13.sp,
            color = tokens.muted,
        )
    }
}
