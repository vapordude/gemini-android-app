# Screens — canonical shape

Most screens in this app are the same shape: a top app bar with a title and
maybe a back button or a few actions, a scrollable body, sometimes a FAB or
a bottom action row. Roll all of that into one wrapper —
`com.gemini.ui.AppScreen` — and use it everywhere.

The only screens that legitimately deviate are:

| Screen | Why it's different |
| --- | --- |
| `ChatScreen` | Owns a drawer + composer + streaming list; the chat surface is the product. |
| `LoginScreen` | First-run, no nav chrome on purpose. |
| `SettingsSheet` | Modal bottom sheet, not a full page. |

Everything else — trace viewer, deployment configs, model picker,
emdash collection browser, future devops panels — uses `AppScreen`.

## Minimum example

```kotlin
@Composable
fun MyScreen() {
    AppScreen(title = "My screen") {
        Text("Hello.")
    }
}
```

That's it. Material 3, dark-first tokens from `GeminiTheme`, center-aligned
title, 16dp content padding, vertical scrolling wired in.

## With back nav + actions

```kotlin
@Composable
fun MyScreen(onBack: () -> Unit) {
    AppScreen(
        title = "My screen",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = { /* refresh */ }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        },
    ) {
        // body
    }
}
```

## With a FAB

```kotlin
AppScreen(
    title = "Deployments",
    floatingActionButton = {
        ExtendedFloatingActionButton(
            onClick = { /* add */ },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("New profile") },
        )
    },
) {
    // body
}
```

## With a LazyColumn body

`AppScreen` wraps its content in a `verticalScroll(rememberScrollState())`
by default. If the body owns its own scrolling (LazyColumn, LazyVerticalGrid,
HorizontalPager), turn that off:

```kotlin
AppScreen(
    title = "Traces",
    scrollable = false,
    contentPadding = PaddingValues(0.dp),
) {
    LazyColumn(contentPadding = PaddingValues(16.dp)) { items(events) { ... } }
}
```

## When to add a slot

`AppScreen` has five slots: `navigationIcon`, `actions`, `floatingActionButton`,
`bottomBar`, `content`. Resist adding a sixth. If two screens want the same
new affordance — a search field in the top bar, a snackbar host, a drawer —
add the slot to `AppScreen` and migrate both, rather than each rolling their
own variant.

## Theming

Don't override colors inside a screen. Tokens live in
`ui-components/.../Theme.kt`, mirrored by `docs/STYLES.md`. If a color
needs to change, change the token and every screen follows.

## Where to put a new screen

```
app/src/main/kotlin/com/gemini/app/ui/<feature>/<ScreenName>.kt
```

One screen per file. Companion ViewModel in the same package if non-trivial.
Sub-composables that are reused across screens belong in `ui-components`;
private helpers stay in the screen file.
