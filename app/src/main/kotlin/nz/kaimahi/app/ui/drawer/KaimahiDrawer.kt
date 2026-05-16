package nz.kaimahi.app.ui.drawer

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.kaimahi.ui.KaimahiLogo
import nz.kaimahi.ui.KaimahiLogoStyle
import nz.kaimahi.ui.KaimahiSigil
import nz.kaimahi.ui.LocalKaimahiColors

/**
 * Destinations reachable from the left drawer.
 * Ordering here is the order they appear in the drawer.
 */
enum class KaimahiDestination {
    Chat,
    DailyTodo,
    MemoryBrowser,
    TraceViewer,
    Deployments,
    ModelPicker,
    Settings,
    About,
}

/**
 * Lightweight project entry passed into the drawer. The drawer doesn't
 * know about ChatStore directly — the chat host maps Store.Entry into
 * this shape so the drawer can stay UI-only.
 */
data class DrawerProject(
    val name: String,
    val displayName: String,
    val whenLabel: String,
    val messageCount: Int,
    val archived: Boolean,
)

/**
 * Cathedral-spec drawer (Frame 1 in docs/design/frame-shell.jsx). 350dp
 * width on a 412dp baseline, dark surface, Cormorant Garamond title +
 * mono caps tagline, kōura badges, whero active-row stripe.
 *
 * Render this as the `drawerContent` of a `ModalNavigationDrawer`.
 */
@Composable
fun KaimahiDrawerContent(
    activeDestination: KaimahiDestination,
    activeProject: String?,
    projects: List<DrawerProject>,
    archivedCount: Int,
    onDestinationSelected: (KaimahiDestination) -> Unit,
    onNewChat: () -> Unit,
    onProjectSelected: (DrawerProject) -> Unit,
    onArchiveProject: (DrawerProject) -> Unit,
    onUnarchiveProject: (DrawerProject) -> Unit,
    onRenameProject: (DrawerProject) -> Unit,
    onDeleteProject: (DrawerProject) -> Unit,
    appVersion: String = "v0.3.0",
    activeModelLabel: String? = null,
) {
    val tokens = LocalKaimahiColors.current
    var archivedExpanded by remember { mutableStateOf(false) }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(350.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header — koru 44dp + "Kaimahi" Cormorant + tagline mono caps
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                KaimahiLogo(size = 44.dp, style = KaimahiLogoStyle.Brand)
                Column {
                    Text(
                        text = "Kaimahi",
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        fontSize = 26.sp,
                        color = tokens.textStrong,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "YOUR LOCAL AI WORKER",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = 1.4.sp,
                        color = tokens.muted,
                    )
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)

            // + New chat — whero pill, full width
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            ) {
                NewChatPill(onClick = onNewChat)
            }

            // Projects section
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                DrawerSectionLabel("PROJECTS")
                val activeCount = projects.count { !it.archived }
                val countLabel = when {
                    activeCount == 0 && archivedCount == 0 -> "empty"
                    archivedCount == 0 -> "$activeCount active"
                    activeCount == 0 -> "$archivedCount archived"
                    else -> "$activeCount active · $archivedCount archived"
                }
                Text(
                    text = countLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = tokens.muted,
                )
            }

            // Project list (active, then optional archived toggle)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val active = projects.filter { !it.archived }
                if (active.isEmpty() && archivedCount == 0) {
                    item {
                        EmptyProjectsHint()
                    }
                } else {
                    items(active, key = { it.name }) { p ->
                        ProjectMini(
                            project = p,
                            active = p.name == activeProject,
                            onClick = { onProjectSelected(p) },
                            onArchive = { onArchiveProject(p) },
                            onRename = { onRenameProject(p) },
                            onDelete = { onDeleteProject(p) },
                        )
                    }
                }
                if (archivedCount > 0) {
                    item {
                        ArchivedToggle(
                            count = archivedCount,
                            expanded = archivedExpanded,
                            onClick = { archivedExpanded = !archivedExpanded },
                        )
                    }
                    if (archivedExpanded) {
                        val archived = projects.filter { it.archived }
                        items(archived, key = { "arch-${it.name}" }) { p ->
                            ProjectMini(
                                project = p,
                                active = false,
                                dim = true,
                                onClick = { onProjectSelected(p) },
                                onArchive = { onUnarchiveProject(p) },
                                onRename = { onRenameProject(p) },
                                onDelete = { onDeleteProject(p) },
                            )
                        }
                    }
                }
            }

            // Tools section header
            DrawerSectionLabel(
                "TOOLS",
                modifier = Modifier.padding(start = 22.dp, top = 12.dp, bottom = 6.dp),
            )
            // Tool items
            DrawerItem(
                label = "Chat",
                icon = DrawerIcons.Chat,
                active = activeDestination == KaimahiDestination.Chat,
                onClick = { onDestinationSelected(KaimahiDestination.Chat) },
            )
            DrawerItem(
                label = "Daily todo",
                icon = DrawerIcons.Todo,
                active = activeDestination == KaimahiDestination.DailyTodo,
                onClick = { onDestinationSelected(KaimahiDestination.DailyTodo) },
            )
            DrawerItem(
                label = "Memory browser",
                icon = DrawerIcons.Memory,
                active = activeDestination == KaimahiDestination.MemoryBrowser,
                onClick = { onDestinationSelected(KaimahiDestination.MemoryBrowser) },
            )
            DrawerItem(
                label = "Trace viewer",
                icon = DrawerIcons.Trace,
                active = activeDestination == KaimahiDestination.TraceViewer,
                onClick = { onDestinationSelected(KaimahiDestination.TraceViewer) },
            )
            DrawerItem(
                label = "Deployments",
                icon = DrawerIcons.Deploy,
                active = activeDestination == KaimahiDestination.Deployments,
                onClick = { onDestinationSelected(KaimahiDestination.Deployments) },
            )
            DrawerItem(
                label = "Model picker",
                icon = DrawerIcons.Model,
                hint = activeModelLabel,
                active = activeDestination == KaimahiDestination.ModelPicker,
                onClick = { onDestinationSelected(KaimahiDestination.ModelPicker) },
            )
            DrawerItem(
                label = "Settings",
                icon = DrawerIcons.Settings,
                active = activeDestination == KaimahiDestination.Settings,
                onClick = { onDestinationSelected(KaimahiDestination.Settings) },
            )
            DrawerItem(
                label = "About",
                icon = DrawerIcons.About,
                active = activeDestination == KaimahiDestination.About,
                onClick = { onDestinationSelected(KaimahiDestination.About) },
            )

            Divider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
                modifier = Modifier.padding(top = 8.dp),
            )

            // Footer — v + Cathedral AI mono caps + faint sigil
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 22.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        appVersion,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = tokens.muted,
                        letterSpacing = 0.8.sp,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "CATHEDRAL AI",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = tokens.disabled,
                        letterSpacing = 1.2.sp,
                    )
                }
                KaimahiSigil(
                    size = 22.dp,
                    modifier = Modifier.alpha(0.3f),
                )
            }
        }
    }
}

@Composable
private fun NewChatPill(onClick: () -> Unit) {
    val tokens = LocalKaimahiColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(28.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "+",
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            "New chat",
            fontSize = 14.5f.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimary,
            letterSpacing = 0.15.sp,
        )
    }
    // Silence unused-tokens warning when light/stealth variants land
    @Suppress("UNUSED_EXPRESSION") tokens
}

@Composable
private fun DrawerSectionLabel(text: String, modifier: Modifier = Modifier) {
    val tokens = LocalKaimahiColors.current
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.5f.sp,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Medium,
        color = tokens.muted,
        modifier = modifier,
    )
}

@Composable
private fun DrawerItem(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
    dim: Boolean = false,
) {
    val tokens = LocalKaimahiColors.current
    val contentColor = when {
        dim -> tokens.muted
        active -> tokens.textStrong
        else -> MaterialTheme.colorScheme.onSurface
    }
    val iconColor = when {
        dim -> tokens.muted
        active -> tokens.brand
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val background = if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    val leftStripe = if (active) tokens.brand else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 2dp left stripe — only visible when active. Hack: a thin Box.
        Box(
            modifier = Modifier
                .width(2.dp)
                .height(20.dp)
                .background(leftStripe),
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            fontSize = 15.sp,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (hint != null) {
            Text(
                text = hint,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = tokens.muted,
                letterSpacing = 0.4.sp,
            )
        }
    }
}

@Composable
private fun ProjectMini(
    project: DrawerProject,
    active: Boolean,
    onClick: () -> Unit,
    onArchive: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    dim: Boolean = false,
) {
    val tokens = LocalKaimahiColors.current
    var expanded by remember(project.name) { mutableStateOf(false) }
    val rowBg = if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    val border = if (active) tokens.brand else Color.Transparent
    val nameColor = when {
        dim -> tokens.muted
        active -> tokens.textStrong
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg, RoundedCornerShape(10.dp))
            .clickable { if (expanded) expanded = false else onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .alpha(if (dim && !expanded) 0.6f else 1f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (active) tokens.brand else tokens.disabled, CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = project.displayName,
                    fontSize = 14.sp,
                    color = nameColor,
                    maxLines = 1,
                )
                Text(
                    text = project.whenLabel,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = tokens.muted,
                    letterSpacing = 0.4.sp,
                )
            }
            CountBadge(count = project.messageCount)
            // Tap once = open; long-press would show actions, but Compose
            // long-press wiring is heavy; instead we expose an ellipsis
            // that toggles the swipe-action row below.
            Text(
                text = "⋯",
                fontSize = 16.sp,
                color = tokens.muted,
                modifier = Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 4.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Divider(
                color = MaterialTheme.colorScheme.outlineVariant,
                thickness = 1.dp,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(label = "RENAME") { expanded = false; onRename() }
                ActionChip(
                    label = if (project.archived) "UNARCHIVE" else "ARCHIVE",
                ) { expanded = false; onArchive() }
                ActionChip(label = "DELETE", danger = true) {
                    expanded = false; onDelete()
                }
            }
        }
    }
    @Suppress("UNUSED_EXPRESSION") border
}

@Composable
private fun CountBadge(count: Int) {
    val tokens = LocalKaimahiColors.current
    Box(
        modifier = Modifier
            .background(tokens.signal.copy(alpha = 0.14f), RoundedCornerShape(11.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = count.toString(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = tokens.signal,
        )
    }
}

@Composable
private fun ArchivedToggle(count: Int, expanded: Boolean, onClick: () -> Unit) {
    val tokens = LocalKaimahiColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (expanded) "▾" else "▸",
            fontSize = 11.sp,
            color = tokens.muted,
        )
        Text(
            text = "ARCHIVED",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = count.toString(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = tokens.muted,
        )
    }
}

@Composable
private fun ActionChip(label: String, danger: Boolean = false, onClick: () -> Unit) {
    val tokens = LocalKaimahiColors.current
    val color = if (danger) tokens.danger else MaterialTheme.colorScheme.onSurfaceVariant
    val borderColor = if (danger) tokens.danger.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .background(Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            color = color,
        )
    }
    @Suppress("UNUSED_EXPRESSION") borderColor
}

@Composable
private fun EmptyProjectsHint() {
    val tokens = LocalKaimahiColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            "No projects yet.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Start a chat and it'll live here.",
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 13.sp,
            color = tokens.muted,
        )
    }
}
