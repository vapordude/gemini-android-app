package com.gemini.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

/**
 * Canonical full-page screen scaffold. Every non-chat, non-modal screen in
 * the app should reach for this rather than rolling its own Scaffold +
 * TopAppBar boilerplate. The five slots (navigationIcon, actions,
 * floatingActionButton, bottomBar, content) cover every case we currently
 * have; if you need a sixth slot, push the case back and we'll widen it
 * deliberately.
 *
 * Tasteful defaults:
 *   - CenterAlignedTopAppBar (matches ChatScreen / settings tone)
 *   - 16dp content padding
 *   - Vertical scroll wired in so simple list/form screens don't have to
 *     remember to opt in. Disable with `scrollable = false` when the
 *     content owns its own scrolling (e.g. LazyColumn).
 *
 * Theming comes from the surrounding [GeminiTheme]. Don't override colors
 * here — change the tokens in Theme.kt / STYLES.md instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior: TopAppBarScrollBehavior =
        TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title) },
                navigationIcon = navigationIcon,
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
    ) { innerPadding ->
        val base = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .padding(contentPadding)
        Column(modifier = if (scrollable) base.verticalScroll(rememberScrollState()) else base) {
            content()
        }
    }
}
