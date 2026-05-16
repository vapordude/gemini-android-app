# Screens — canonical shape

Most screens in this app are the same shape: a top app bar with a title and
maybe a back button or a few actions, a scrollable body, sometimes a FAB or
a bottom action row. Roll all of that into one wrapper —
`nz.kaimahi.ui.AppScreen` — and use it everywhere.

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

That's it. Material 3, dark-first tokens from `KaimahiTheme`, center-aligned
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

## The API is the schema — canonical renderers

The agent already speaks typed events. The runtime already exposes
typed info. The user doesn't need a parallel "UI schema" for the agent
to fill out — the existing canonical shapes ARE the schema:

| Shape | Lives in | Canonical renderer |
| --- | --- | --- |
| `AgentEvent` | `domain` | `ui-components/canonical/AgentTranscript.kt` |
| `RuntimeInfo` + `ModelHandle` | `domain` | `RuntimeInfoPanel.kt` |
| `TraceEvent` | `domain` | `TraceList.kt` / `TraceEventRow` |
| `EmdashDiff` | `domain` | `EmdashDiffView.kt` |

Each renderer is exhaustive over its sealed type — adding a new
variant upstream stops the renderer compiling, which is the feature.
"AI fills out a form" reduces to "AI emits the typed events it
already emits". The UI's job is **pure-data → Compose**.

```kotlin
@Composable
fun AgentSessionScreen(events: List<AgentEvent>, info: RuntimeInfo) {
    AppScreen(title = "Agent") {
        RuntimeInfoPanel(info = info)
        Spacer(Modifier.height(16.dp))
        AgentTranscript(events = events)
    }
}
```

Streaming = appending to the list = `AnimatedVisibility` fade-in per
new card. No parser, no schema drift.

### Agent-driven shaping: `Hint`

The agent can still influence presentation **within** the typed
surface — it isn't locked into one rendering per event variant.
Every `AgentEvent` carries an optional `Hint`:

```kotlin
data class Hint(
    val tone: Tone = Tone.Default,         // Success | Warn | Info | Learning | Danger
    val emphasis: Emphasis = Emphasis.Normal, // Subtle | Normal | Strong
)
```

Tones map onto existing tokens (`ngahere`, `amber`, `pounamu`,
`kowhai`, `coral`); emphasis maps onto weight + muting. Adding a new
visual concept is a `BRAND.md` change first, not a `Hint` extension.
So the agent gets expressivity, the brand stays bounded.

Example:

```kotlin
emit(AgentEvent.Message(
    text = "Deploy succeeded.",
    hint = Hint(tone = Tone.Success, emphasis = Emphasis.Strong),
))
emit(AgentEvent.Thinking(
    text = "Considering the licence implications…",
    hint = Hint(emphasis = Emphasis.Subtle),
))
```

The renderer reads `event.hint` and picks among existing tokens.
Defaults are sensible, so old call sites keep working untouched.

### When NOT to add a new canonical type

Don't invent a new section/field/widget type unless the typed event
stream genuinely can't express what the agent wants to communicate.
If the agent needs to surface a result the existing events don't
cover, the right move is to add a variant upstream (in `domain`),
which forces the renderer update in the same diff.

## Where to put a new screen

```
app/src/main/kotlin/nz/kaimahi/app/ui/<feature>/<ScreenName>.kt
```

One screen per file. Companion ViewModel in the same package if non-trivial.
Sub-composables that are reused across screens belong in `ui-components`;
private helpers stay in the screen file.
