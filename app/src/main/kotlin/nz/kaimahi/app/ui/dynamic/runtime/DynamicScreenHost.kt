package nz.kaimahi.app.ui.dynamic.runtime

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nz.kaimahi.bridge.screens.ScreenRegistry

/**
 * Host for one dynamic screen identified by id. Pulls the spec + data
 * from the [ScreenRegistry], renders, and threads interactions back
 * to the registry (checklist toggle) or to optional tool-invoke
 * callbacks (button / item / add).
 *
 * The toggle path is pure data — bounded, reversible, doesn't need
 * approval — so the host handles it directly. Button / item / +Add
 * tool invocations route through [onInvokeTool] which the integrating
 * Activity wires to its tool runner. v1 ships with a default no-op
 * that just refreshes the screen; the host stays usable even when
 * the integration isn't wired.
 *
 * If the spec doesn't exist (user deleted it from chat) we render a
 * small "screen not found" notice rather than crash.
 */
@Composable
fun DynamicScreenHost(
    screenId: String,
    registry: ScreenRegistry,
    onDrawerOpen: () -> Unit,
    onInvokeTool: (toolName: String, args: Map<String, Any?>) -> Unit = { _, _ -> },
) {
    var refreshTick by remember(screenId) { mutableStateOf(0) }
    val spec = remember(screenId, refreshTick) { registry.get(screenId) }
    val data = remember(screenId, refreshTick) { registry.dataStore(screenId).load() }

    LaunchedEffect(screenId) {
        // Bump the refresh tick on first composition so the screen is
        // fresh after navigating to it (the agent tool path mutates
        // files directly; we don't observe them, so we re-read).
        refreshTick++
    }

    val current = spec
    if (current == null) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Screen not found: $screenId")
        }
        return
    }

    DynamicScreenView(
        spec = current,
        data = data,
        onDrawerOpen = onDrawerOpen,
        onChecklistToggle = { widgetKey, itemIndex ->
            registry.dataStore(screenId).transform { obj ->
                val widget = current.widgetByKey(widgetKey)
                val dataKey = (widget as? nz.kaimahi.domain.screens.WidgetSpec.Checklist)?.dataKey
                    ?: return@transform obj
                val arr = obj.optJSONArray(dataKey) ?: return@transform obj
                if (itemIndex !in 0 until arr.length()) return@transform obj
                val item = arr.optJSONObject(itemIndex) ?: return@transform obj
                item.put("done", !item.optBoolean("done", false))
                arr.put(itemIndex, item)
                obj.put(dataKey, arr)
                obj
            }
            refreshTick++
        },
        onChecklistAdd = { _, toolName ->
            if (toolName != null) onInvokeTool(toolName, mapOf("screen_id" to screenId))
            refreshTick++
        },
        onButtonTap = { toolName, args ->
            if (toolName != null) onInvokeTool(toolName, args.plus("screen_id" to screenId))
            refreshTick++
        },
        onItemTap = { toolName, item ->
            if (toolName != null) {
                val itemArgs = mutableMapOf<String, Any?>("screen_id" to screenId)
                for (k in item.keys()) itemArgs[k] = item.opt(k)
                onInvokeTool(toolName, itemArgs)
            }
            refreshTick++
        },
    )
}

private fun nz.kaimahi.domain.screens.ScreenSpec.widgetByKey(key: String): nz.kaimahi.domain.screens.WidgetSpec? =
    when (this) {
        is nz.kaimahi.domain.screens.ScreenSpec.Stack -> widgets.firstOrNull { it.key == key }
    }
