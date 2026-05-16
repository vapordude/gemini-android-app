# Kaimahi — brand identity

> *Kaimahi* — Te Reo Māori for "worker", "employee". The tool does the
> work; the operator stays in charge.

## Voice

- **Sovereign.** Local-first by default. Cloud is a tool, not a master.
- **Grounded.** Workshop language. Real materials. No hype.
- **Specific.** Numbers, file paths, line numbers. No "leveraging".
- **Generous.** Credit upstream loudly (`MIHI.md`).

If a sentence would feel wrong tattooed on a hammer handle, rewrite it.

## Visual references

Three materials anchor the palette. They aren't decorative — they
choose roles.

| Material | Role | Why |
| --- | --- | --- |
| **Pounamu** (greenstone) | Brand primary — interactive surfaces, focus rings, the logo. | Treasured, durable, settles into a deep teal that's nothing like Google blue. |
| **Kowhai** (yellow flower) | Signal accent — learning, memory, "I noticed something". | Bright but not alarming; a moment of attention. |
| **Kauri** (timber) | Acting accent — agent in motion, tool calls in flight. | Warm wood; "work is happening". |
| **Ngahere** (forest) | Success / healthy state. | Same family as pounamu, softer green. |

## Palette

Hex values, both schemes. Live in `ui-components/.../Theme.kt`.

### Dark (default)

```
bg            #0E1416    deep neutral, faint teal undertone
surface-1     #1A2226    cards, top bar
surface-2     #232E33    raised
surface-3     #2C383D    hover / pressed on raised
line          #243035
line-strong   #34434A
text-strong   #F2F5F5
text          #DDE5E6
text-secondary#A8B5B7
text-muted    #6F7D80
text-disabled #4A5559

pounamu       #4FA3A3    primary
kowhai        #E8C75F    signal
kauri         #BD7B5C    act
ngahere       #7BC18D    success
amber         #E0B25B    warn
coral         #E07A6B    error (warm, not pure red)
```

### Light

Same roles, inverted luminance. Pounamu darkens to `#2E7878` for
contrast on white. See `Theme.kt::LightExtended`.

## Brand mark

`KaimahiLogo` (Compose Canvas, no raster assets):

- **`Style.Brand`** — gradient stroke pounamu → ngahere → kowhai → kauri.
  Hero, login, the about screen. Has a small kowhai dot above the bar.
- **`Style.Solid`** — single-color, picks up `tint`. Top app bar.
- **`Style.Outline`** — hairline weight. Chips, dense lists.

The shape: a clean geometric "K" with a strong horizontal bar through
the join. The bar reads two ways — a worker's level (the literal tool)
and a horizon line. Both fit.

Size grid: 16dp / 24dp / 32dp / 48dp / 64dp. The mark is normalized to a
24-unit square; any size works, but those are the canonical anchors.

## Status badges

`KaimahiBadge` (top-bar status pills). One affordance per concept; no
overlap.

| Kind | Visual | Means |
| --- | --- | --- |
| **Cloud** | pounamu pill, small filled dot | this turn is going to the cloud Gemini path |
| **Local** | kauri pill, small filled dot | this turn is going to the on-device LM |
| **Dual** | outline + gradient dot | both authenticated; policy picks per call |
| **Memory** | kowhai pill | persistent memory recalled into this turn |
| **Delegate** | brand-outline pill, gradient dot | a vendor NPU/GPU delegate is engaged |

Tap → opens the runtime info sheet (the same data `/info` returns).

## Brand gradient

```
0%    pounamu  #4FA3A3
40%   ngahere  #7BC18D
75%   kowhai   #E8C75F
100%  kauri    #BD7B5C
```

Use cases (and only these):

- The brand mark in `Style.Brand`.
- The streaming/loading indicator in the chat composer.
- The "memory recalled" halo behind the agent's leading message of a
  resumed session.
- Onboarding hero.

Not used: top app bars, buttons, dividers, list rows, anything that
ships repeatedly. Material 3 tokens (`primary`, `primaryContainer`,
etc.) do all the heavy chrome work; the gradient stays scarce so it
keeps meaning something.

## Typography

System sans, Material 3 type scale, slight negative tracking on
headlines (`-0.6 sp` on `headlineLarge`). Positive tracking on label
sizes (`+0.2 sp`) for clarity at 12–14sp. Custom face is a follow-up;
when it lands it'll be a humanist sans with strong terminals and a
distinct lowercase `a`.

## Shape

```
extraSmall   6 dp   dense chips
small        12 dp  chips, list rows
medium       16 dp  cards, dropdowns
large        20 dp  dialogs, sheets
extraLarge   28 dp  pill buttons, chat composer
```

The chat composer and primary CTAs are pills (28dp). Cards are 16dp.
Dialogs are 20dp. Avoid rectangular corners except for full-bleed
surfaces.

## Motion

- **Standard** 200 ms ease-out for state changes.
- **Emphasized** 320 ms for screen transitions.
- **Streaming** the brand gradient drifts left-to-right at 5 s/cycle —
  audible-on-glance, never strobing.
- Reduced-motion respects the OS setting; gradient swap to a static
  shimmer.

## Imagery

- No stock photos.
- No avatars for the agent.
- Screenshots in docs: real terminal sessions, not mocks.
- The launcher icon is the brand mark on a `#0E1416` square with a
  one-line subtle border.

## What to push back on

- Anything that looks like a "powered by Google" badge.
- Anything that hides what the agent is doing.
- Any color outside this palette unless the OS provides it (system
  notifications, accessibility settings).
- Decorative motion. Motion describes work happening; if there's no
  work, there's no motion.
