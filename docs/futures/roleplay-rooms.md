# Roleplay rooms — persona cards, themes, multi-endpoint scenes

> Status: **planning**. Captured from chat. Sits in the queue alongside
> swarm workers; shares the **Endpoint** primitive with that feature.

## What it is

A room contains a **scene** (shared world / context) and a cast of
**characters**. Each character has a **persona card** (name,
description, system prompt, optional avatar, optional voice) bound to
an **endpoint** (local Gemma, cloud Gemini, remote Crimson, friend's
Lux). The user types into the room; one or many characters respond.
Themes (visual palette, fonts, ornaments) are per-room.

SillyTavern is the obvious reference — well-trodden card format,
established UX. Kaimahi's version differs in three places: (1) any
character can be backed by a different endpoint, (2) the visual is
Cathedral-family (whero / kōura / ember on black, not the SillyTavern
default), (3) memory is project-scoped, not character-scoped.

## Persona card schema

Adopt SillyTavern Character Card V2 verbatim where sensible —
existing card libraries become instantly importable. Extension fields
hold our endpoint binding.

```kotlin
data class PersonaCard(
    // SillyTavern V2 core
    val name: String,
    val description: String,
    val personality: String,
    val scenario: String,
    val firstMessage: String,
    val messageExamples: String,
    val systemPrompt: String,
    val postHistoryInstructions: String,
    val alternateGreetings: List<String>,
    val tags: List<String>,
    val creator: String,
    val characterVersion: String,
    val avatarPath: String?,            // PNG with embedded JSON metadata

    // Kaimahi extensions (under `extensions.kaimahi.*` in the JSON)
    val endpointId: String,             // which endpoint drives this character
    val voice: VoiceConfig? = null,     // TTS endpoint / preset / pitch / speed
    val themeId: String? = null,        // per-character theme override
    val temperature: Float? = null,     // optional per-character sampling
    val topK: Int? = null,
)

data class VoiceConfig(
    val provider: String,               // "android-tts" | "elevenlabs" | "local-piper" …
    val voiceId: String,
    val pitch: Float = 1.0f,
    val speed: Float = 1.0f,
)
```

Cards live in `filesDir/personas/<slug>.json`. PNG avatars (the
SillyTavern style — PNG with the JSON spec encoded in a tEXt chunk)
are parsed on import; the JSON is hydrated, the image goes to
`filesDir/personas/<slug>.png`.

## Room schema

```kotlin
data class Room(
    val id: String,
    val name: String,
    val scene: String,                  // world description; shared system context
    val characters: List<RoomCharacter>, // ordered for default turn-taking
    val themeId: String?,               // visual theme; null = default Cathedral dark
    val memoryMode: MemoryMode,
    val turnMode: TurnMode,
    val archived: Boolean = false,
    val createdAt: Long,
    val lastTurnAt: Long,
)

data class RoomCharacter(
    val cardSlug: String,
    val nameOverride: String? = null,   // optional rename inside this room
    val muted: Boolean = false,         // skip in auto turn-taking
)

enum class MemoryMode {
    Shared,      // all characters see all messages
    PerCharacter,// each sees only what's addressed to them + their own replies
    Narrator     // the narrator endpoint sees everything; characters see filtered
}

enum class TurnMode {
    Manual,      // user picks who responds via @mentions or a tap
    RoundRobin,  // cycle through unmuted characters in order
    NarratorDriven, // a narrator endpoint chooses who responds (and may
                 // multi-cast — "Hemi looks up; Marama answers first")
    Parallel     // every character responds to every user message (chaos)
}
```

Rooms persist under `filesDir/rooms/<id>/`:

- `room.json` — the schema above
- `transcript.json` — chronological message list (rotated/compacted as
  it grows, same pattern as `ChatStore`)
- `memory/` — per-character or shared memory store, format from the
  Rust agent-core `MemoryStore`

## Visual / theme

Themes are a thin layer over the Cathedral palette (the locked tokens
in `ui-components/Theme.kt`). A theme overrides:

- Primary / secondary / tertiary accents (defaulting to whero / kōura /
  ember)
- Background tier (default OLED black; "warm paper" for cosy scenes;
  "stealth" for nightstand)
- Optional ornament (a corner glyph behind the chat — kept subtle)
- Optional typeface override (display font — Cormorant / Iowan / Atkinson
  Hyperlegible swatch)

Themes live as JSON in `filesDir/themes/<id>.json`. Three ship-with
themes for v1:

| Theme | Background | Primary | Use case |
|---|---|---|---|
| `cathedral-dark` (default) | Black `#0A0A0A` | Whero `#C1272D` | Standard sovereign scenes |
| `pataka-warm` | Warm paper `#F4ECE4` | Antique whero `#B22228` | Cosy / fireside scenes |
| `pō-stealth` | OLED `#000000` | Dim whero `#8b1a1e` | Late-night, low-light |

## UX shape

New drawer destinations:

- **Rooms** — list, mirrors Projects layout. Active rooms + Archived.
- **Personas** — card library. Each card preview shows avatar + name +
  endpoint badge. + Import (file picker for SillyTavern PNGs), + New
  (form), search by tag.
- **Endpoints** — shared with swarm workers (see `swarm-workers.md`).

Room screen layout:

- App bar: koru + room name + scene blurb (collapsed; tap to expand) +
  overflow (Edit scene / Add character / Mute character / Change
  theme / Archive room).
- Chat transcript: messages are tagged with the character avatar + name
  + endpoint chip ("Crimson Pi 5"). User messages distinct (whero
  tint).
- Composer at the bottom. Optional `@name` prefix to address a
  character (manual turn mode). In other modes, just type.
- Optional voice playback: each non-user message has a play button
  that hits the configured TTS endpoint.

## Cross-character context handling

Each character's turn:

1. Build the prompt: scene + persona system prompt + the slice of
   transcript visible to this character (depends on `memoryMode`) +
   the current user input.
2. Stream from the character's endpoint (local agent loop or cloud
   chat, depending on `endpoint.kind`).
3. Append the response to the transcript. The next character in the
   turn sequence sees it next round (or not, depending on
   `memoryMode`).

This means a character can be the **local Gemma model** while another
is **cloud Gemini-2.5-pro** — and they pass turns in the same room.
The chat experience is identical from the user's side; under the hood,
each turn routes to a different endpoint.

## Open design questions

- **Narrator role**: optional or mandatory in NarratorDriven mode? Who
  is the narrator? A normal endpoint with a narrator system prompt,
  or a dedicated "scene controller" type? Probably the former — one
  more persona card with a `narrator: true` flag in the extension.
- **Character interruptions**: should a character be allowed to
  interject mid-user-typing? In a long-form scene that's high signal;
  in a short exchange it's noise. Default: no interruptions; opt-in
  per room.
- **Content neutrality**: SillyTavern users push hard on NSFW. Kaimahi
  is content-neutral by stance. Local Gemma models are content-neutral
  by default; cloud Gemini will refuse some content. Surface this
  honestly per-character ("this character can't run on cloud Gemini
  for that scenario — switch to a local endpoint?").
- **Card import surface**: SillyTavern V2 PNG cards contain the JSON
  in a tEXt chunk named `chara`. We parse that natively. V1 cards
  (older format) and JSON-only `.json` cards should also be accepted.
  Detect by file extension + chunk presence.
- **Group memory leakage**: if MemoryMode = PerCharacter, what stops
  one character "seeing" another's reply through transcript inference?
  Honest answer: nothing — it's an honour system per the system
  prompt. We document it; the user picks the mode.
- **Voice cost**: cloud TTS is per-character expensive over a long
  scene. Default to Android system TTS (free, on-device) unless
  user picks otherwise.

## Phasing

| Phase | Scope |
|---|---|
| **v1 — single-character room** | Personas + Rooms drawer routes. One character per room. Manual or RoundRobin turn modes. Shared memory. cathedral-dark theme only. SillyTavern V2 JSON import (JSON only, not PNG). No voice. |
| **v2 — multi-character + PNG import** | Multiple characters per room, RoundRobin / Manual / NarratorDriven turn modes. PNG card import. Per-character endpoint binding visible in the UI. |
| **v3 — themes + voices** | Three ship-with themes selectable per room. Android system TTS for character playback; voice config in the persona card. |
| **v4 — narrator + memory modes** | Narrator agent fully wired; PerCharacter memory mode with the honour-system caveat documented; per-character memory directories. |
| **v5 — parallel turn mode + cross-app endpoints** | Chaos mode. Remote-endpoint personas (Crimson on a Pi 5 driving a character in your phone's room). |

## Cross-references

- Shares the **Endpoint** primitive with `swarm-workers.md`.
- Builds on the **local agent loop** (`PR #18`) for any local-character
  turn.
- Eventually consumes the **DynamicScreen** primitive for per-room
  scene-state widgets (initiative tracker, inventory, scene timer —
  for ttrpg-style sessions).
- TTS is decoupled — same engine should be usable from any chat (not
  just rooms).

## Why this matters for the Cathedral family

A roleplay room is **Lux** territory — companion / entertainment /
counsellors / friends / partners — but it ships in Kaimahi (the free
tier) because the primitive is too useful to gate. The Lux commercial
offering builds on top: better-trained character endpoints, voice
quality, longer memory, no-cost turn budget. Kaimahi gets the
free-but-fully-usable version; Lux gets the polish.
