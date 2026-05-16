package nz.kaimahi.app.ui.dynamic.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import nz.kaimahi.domain.screens.ButtonStyle
import nz.kaimahi.domain.screens.HeadingLevel
import nz.kaimahi.domain.screens.ScreenSpec
import nz.kaimahi.domain.screens.StripeTone
import nz.kaimahi.domain.screens.WidgetSpec
import nz.kaimahi.ui.KaimahiCaption
import nz.kaimahi.ui.KaimahiLogo
import nz.kaimahi.ui.KaimahiLogoStyle
import nz.kaimahi.ui.LocalKaimahiColors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Renders a [ScreenSpec] dispatched by widget kind. Stateless — the
 * host owns the data (JSONObject) and the action callbacks. Drawer
 * Menu button + serif title sit in the app bar matching the rest of
 * the dynamic-screen surfaces (daily-todo, memory-browser, front-page).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DynamicScreenView(
    spec: ScreenSpec,
    data: JSONObject,
    onDrawerOpen: () -> Unit,
    onChecklistToggle: (widgetKey: String, itemIndex: Int) -> Unit,
    onChecklistAdd: (widgetKey: String, addToolName: String?) -> Unit,
    onButtonTap: (toolName: String?, args: Map<String, Any?>) -> Unit,
    onItemTap: (toolName: String?, item: JSONObject) -> Unit,
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
                            spec.title,
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when (spec) {
            is ScreenSpec.Stack -> StackBody(
                widgets = spec.widgets,
                data = data,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onChecklistToggle = onChecklistToggle,
                onChecklistAdd = onChecklistAdd,
                onButtonTap = onButtonTap,
                onItemTap = onItemTap,
            )
        }
    }
}

@Composable
private fun StackBody(
    widgets: List<WidgetSpec>,
    data: JSONObject,
    modifier: Modifier = Modifier,
    onChecklistToggle: (String, Int) -> Unit,
    onChecklistAdd: (String, String?) -> Unit,
    onButtonTap: (String?, Map<String, Any?>) -> Unit,
    onItemTap: (String?, JSONObject) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(widgets, key = { it.key }) { w ->
            when (w) {
                is WidgetSpec.Heading -> HeadingWidget(w)
                is WidgetSpec.Body -> BodyWidget(w)
                is WidgetSpec.InfoCard -> InfoCardWidget(w)
                is WidgetSpec.Divider -> Divider(color = MaterialTheme.colorScheme.outlineVariant)
                is WidgetSpec.ItemList -> ItemListWidget(
                    w,
                    data,
                    onItemTap = { item -> onItemTap(w.onItemTapTool, item) },
                )
                is WidgetSpec.Checklist -> ChecklistWidget(
                    w,
                    data,
                    onToggle = { idx -> onChecklistToggle(w.key, idx) },
                    onAdd = { onChecklistAdd(w.key, w.onAddTool) },
                )
                is WidgetSpec.Button -> ButtonWidget(
                    w,
                    onTap = { onButtonTap(w.onTapTool, w.onTapArgs) },
                )
            }
        }
    }
}

@Composable
private fun HeadingWidget(w: WidgetSpec.Heading) {
    val tokens = LocalKaimahiColors.current
    val (size, weight) = when (w.level) {
        HeadingLevel.H1 -> 24.sp to FontWeight.Medium
        HeadingLevel.H2 -> 19.sp to FontWeight.Medium
        HeadingLevel.H3 -> 16.sp to FontWeight.SemiBold
    }
    Text(
        text = w.text,
        fontFamily = FontFamily.Serif,
        fontWeight = weight,
        fontSize = size,
        color = tokens.textStrong,
    )
}

@Composable
private fun BodyWidget(w: WidgetSpec.Body) {
    Text(
        text = w.text,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun InfoCardWidget(w: WidgetSpec.InfoCard) {
    val tokens = LocalKaimahiColors.current
    val stripe = stripeColor(w.stripe, tokens)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        if (stripe != Color.Transparent) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(stripe),
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (w.title != null) {
                Text(
                    w.title,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = tokens.textStrong,
                )
            }
            Text(
                w.body,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ItemListWidget(
    w: WidgetSpec.ItemList,
    data: JSONObject,
    onItemTap: (JSONObject) -> Unit,
) {
    val tokens = LocalKaimahiColors.current
    val arr = data.optJSONArray(w.dataKey) ?: JSONArray()
    if (arr.length() == 0 && w.emptyHint != null) {
        Text(
            w.emptyHint,
            fontStyle = FontStyle.Italic,
            color = tokens.muted,
            fontSize = 13.sp,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable { onItemTap(item) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.optString("label"),
                        fontSize = 14.sp,
                        color = tokens.textStrong,
                    )
                    val meta = item.optString("meta")
                    if (meta.isNotBlank()) {
                        KaimahiCaption(
                            text = meta,
                            fontSize = 10.sp,
                            letterSpacing = 0.4.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistWidget(
    w: WidgetSpec.Checklist,
    data: JSONObject,
    onToggle: (Int) -> Unit,
    onAdd: () -> Unit,
) {
    val tokens = LocalKaimahiColors.current
    val arr = data.optJSONArray(w.dataKey) ?: JSONArray()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (arr.length() == 0 && w.emptyHint != null) {
            Text(
                w.emptyHint,
                fontStyle = FontStyle.Italic,
                color = tokens.muted,
                fontSize = 13.sp,
            )
        }
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val done = item.optBoolean("done", false)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable { onToggle(i) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(
                            if (done) tokens.signal else Color.Transparent,
                            RoundedCornerShape(5.dp),
                        )
                        .border(
                            1.5.dp,
                            if (done) tokens.signal else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(5.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (done) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = Color(0xFF0A0A0A),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.optString("label"),
                        fontSize = 14.sp,
                        color = if (done) tokens.muted else tokens.textStrong,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                    )
                    val meta = item.optString("meta")
                    if (meta.isNotBlank()) {
                        KaimahiCaption(text = meta, fontSize = 10.sp, letterSpacing = 0.4.sp)
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent, RoundedCornerShape(10.dp))
                .border(1.dp, tokens.signal.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                .clickable(onClick = onAdd)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = tokens.signal)
            Text(
                "Add",
                fontSize = 14.sp,
                color = tokens.signal,
            )
        }
    }
}

@Composable
private fun ButtonWidget(w: WidgetSpec.Button, onTap: () -> Unit) {
    val tokens = LocalKaimahiColors.current
    val (bg, fg) = when (w.style) {
        ButtonStyle.Primary -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
        ButtonStyle.Secondary -> Color.Transparent to tokens.textStrong
        ButtonStyle.Destructive -> tokens.danger to Color.White
    }
    val borderColor = when (w.style) {
        ButtonStyle.Secondary -> MaterialTheme.colorScheme.outline
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(bg, RoundedCornerShape(28.dp))
            .border(1.dp, borderColor, RoundedCornerShape(28.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            w.label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
        )
    }
}

private fun stripeColor(t: StripeTone, tokens: nz.kaimahi.ui.KaimahiExtendedColors): Color = when (t) {
    StripeTone.None -> Color.Transparent
    StripeTone.Whero -> tokens.brand
    StripeTone.Koura -> tokens.signal
    StripeTone.Ember -> tokens.act
    StripeTone.Muted -> tokens.muted
}
