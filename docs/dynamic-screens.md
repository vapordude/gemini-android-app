# Dynamic screens

> Status: **shipped v1**. Agent can scaffold screens; user can pin
> them; checklist toggle works directly; button / item tap tool
> invocation wires through the app layer.

The agent emits a JSON `ScreenSpec` through the `create_screen` tool.
The host parses, persists, and renders. Data lives separately under
`<filesDir>/screens/<id>/data.json` and is mutated by tools or — for
the bounded reversible case of a checklist `done` toggle — directly
by the renderer.

## Why

Everything in the app that isn't chat is a screen the agent
*should* have been able to build: daily todo, daily front page,
memory browser. v0 ships those as hand-coded Compose. v1 (this) ships
the generic system so the agent can scaffold new ones at runtime:

> *"build me a daily-habits screen with a checklist for water /
> stretch / meditate, and add a 'reset week' button"*

becomes a single `create_screen` tool call.

## Tools

| Tool | Destructive | Args | Returns |
|---|---|---|---|
| `create_screen` | yes | `{spec: ScreenSpec JSON}` | confirmation + slug |
| `update_screen` | yes | `{spec: ScreenSpec JSON}` | confirmation |
| `delete_screen` | yes | `{id: string}` | confirmation |
| `list_screens` | no | — | `[{id, title, icon, createdBy, widgetCount}]` |
| `read_screen_data` | no | `{screen_id: string}` | the full data JSON |
| `write_screen_data` | yes | `{screen_id, key, value}` | confirmation |

All six register into the same `ToolRegistry` the cloud function-
calling path and the local agent loop see. Destructive ones go
through the existing approval dialog.

## ScreenSpec JSON

```json
{
  "id": "daily-habits",
  "kind": "stack",
  "title": "Daily habits",
  "icon": "list",
  "createdAt": 1747400000000,
  "createdBy": "agent:gemma4-e2b",
  "widgets": [
    {"type": "heading", "key": "h1", "text": "This week", "level": "h1"},
    {"type": "body", "key": "intro", "text": "Quick check-ins, one tap each."},
    {"type": "checklist", "key": "tasks", "dataKey": "items",
     "emptyHint": "Nothing yet", "onAddTool": "add_habit"},
    {"type": "info_card", "key": "note", "title": "Built by Kaimahi",
     "body": "Tap an item to toggle it done.", "stripe": "koura"},
    {"type": "divider", "key": "d"},
    {"type": "button", "key": "reset", "label": "Reset week",
     "style": "destructive", "onTapTool": "reset_habits"}
  ]
}
```

### Widget catalogue

| `type` | Fields | Notes |
|---|---|---|
| `heading` | `text`, `level: h1\|h2\|h3` | serif, level-sized |
| `body` | `text` | paragraph, wraps |
| `info_card` | `title?`, `body`, `stripe: none\|whero\|koura\|ember\|muted` | optional left stripe |
| `divider` | — | horizontal rule |
| `item_list` | `dataKey`, `emptyHint?`, `onItemTapTool?` | binds to `data[dataKey]: [{label, meta?}]` |
| `checklist` | `dataKey`, `emptyHint?`, `onAddTool?` | binds to `data[dataKey]: [{label, meta?, done}]` — toggle is direct, no tool |
| `button` | `label`, `style: primary\|secondary\|destructive`, `onTapTool?`, `onTapArgs?` | tool call gets `screen_id` added |

### Enum values

- `icon`: `default`, `list`, `note`, `news`, `calendar`, `inbox`, `heart`, `bolt`
- `level`: `h1`, `h2`, `h3`
- `stripe`: `none`, `whero`, `koura`, `ember`, `muted`
- `style`: `primary`, `secondary`, `destructive`

## Data shape

The data file at `<filesDir>/screens/<id>/data.json` is a flat
JSON object keyed by `dataKey`. For a checklist with `dataKey:
"items"`, it might look like:

```json
{
  "items": [
    {"label": "Drink water", "meta": "8 glasses", "done": false},
    {"label": "Stretch", "meta": "5 minutes", "done": true}
  ]
}
```

The agent writes through `write_screen_data` (one key at a time):

```
write_screen_data {
  "screen_id": "daily-habits",
  "key": "items",
  "value": [{"label": "Drink water", "done": false}]
}
```

## Architecture

```
domain/screens/
  ScreenSpec.kt          — sealed types (ScreenSpec, WidgetSpec, enums)

core-bridge/screens/
  ScreenSpecJson.kt      — to/from JSON
  ScreenRegistry.kt      — persists spec + data per id
core-bridge/tools/
  ScreenTools.kt         — six Tool implementations

app/ui/dynamic/runtime/
  DynamicScreen.kt       — Compose renderer (stateless)
  DynamicScreenHost.kt   — wires registry, handles checklist toggle,
                           forwards button/item tool invocations to
                           the integrating Activity
```

## What's deferred to follow-ups

- **Multi-kind screens.** v1 only has `ScreenSpec.Stack`. A
  `SplitScreen` (header + tab strip) and `Grid` would unlock more
  layouts. Not blocking the agent-builds-a-screen use case.
- **Per-widget patching.** `update_screen` replaces the entire widget
  list. A `patch_widget` with `{screen_id, key, fields}` would let
  the agent tweak one heading without re-emitting everything.
- **Live data observation.** v1 reads data.json on screen entry +
  refreshes via a tick counter on every interaction. A `Flow`-backed
  watcher on the file would make multi-source updates (agent writes
  while user is viewing) show up without a navigation round-trip.
- **Validation of dataKey shape.** v1 assumes the agent emits items
  with the right field names (`label`, `meta`, `done`). A schema
  declared inside the widget would let the renderer surface a clear
  error if the data doesn't match.
- **Drawer pin / unpin UX.** v1 lists screens in the drawer
  automatically; an explicit "pin to drawer" toggle (vs. just "exists
  in registry") would let users reorder.
- **Daily-todo migration.** The existing hand-coded `DailyTodoScreen`
  stays; once the agent path is comfortable, daily-todo becomes a
  built-in `ScreenSpec.Stack` rather than a Compose file. Same
  visual, less code.
