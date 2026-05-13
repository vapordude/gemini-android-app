# Kaimahi — visual system (technical reference)

This file is the per-token technical reference: the exact hex values,
the Material 3 role each token plays, and how they map to Compose
tokens in `ui-components/.../Theme.kt`.

For the *why* behind the choices — the brand voice, the three material
references (pounamu / kowhai / kauri), when to use the gradient versus
the neutrals — read **[`BRAND.md`](BRAND.md)** first.

Design language: Material 3, dark-first, low chroma, thin strokes,
slightly tightened type. Brand primary is pounamu (`#4FA3A3`).

---

## 1. Color tokens

Single source of truth: `ui-components/src/main/kotlin/nz/kaimahi/ui/Theme.kt`.
This file mirrors that — when one moves, move both.

### 1.1 Surfaces (dark, primary)

```css
:root {
  --kai-bg:           #0E1416; /* app canvas */
  --kai-surface-1:    #1A2226; /* cards, top app bar */
  --kai-surface-2:    #232E33; /* raised elements */
  --kai-surface-3:    #2C383D; /* hover / pressed on raised */
}
```

### 1.2 Lines

```css
:root {
  --kai-line:         #243035;
  --kai-line-strong:  #34434A;
}
```

### 1.3 Text

```css
:root {
  --kai-text-strong:  #F2F5F5;
  --kai-text:         #DDE5E6;
  --kai-text-sec:     #A8B5B7;
  --kai-text-muted:   #6F7D80;
  --kai-text-dis:     #4A5559;
}
```

### 1.4 Brand colors

```css
:root {
  --kai-pounamu:      #4FA3A3; /* primary interactive */
  --kai-pounamu-on:   #042525;
  --kai-kowhai:       #E8C75F; /* signal / learning */
  --kai-kowhai-on:    #2B2407;
  --kai-kauri:        #BD7B5C; /* agent acting */
  --kai-kauri-on:     #301607;
  --kai-ngahere:      #7BC18D; /* success */
  --kai-amber:        #E0B25B; /* warn */
  --kai-coral:        #E07A6B; /* error (warm, not pure red) */
}
```

### 1.5 Brand gradient

```css
:root {
  --kai-brand-grad: linear-gradient(90deg,
    #4FA3A3 0%,
    #7BC18D 40%,
    #E8C75F 75%,
    #BD7B5C 100%);
}
```

Used for: hero, streaming indicator, learning halo. Never UI chrome.

### 1.6 Light mirror

Same roles, inverted luminance. Pounamu darkens to `#2E7878` for AA
contrast on white. Other tokens follow; see `Theme.kt::LightExtended`.

---

## 2. Shape

```
extraSmall   6 dp   dense chips
small        12 dp  chips, list rows
medium       16 dp  cards, dropdowns
large        20 dp  dialogs, sheets
extraLarge   28 dp  pill buttons, chat composer
```

The chat composer + primary CTAs are pills. Cards are 16dp.

---

## 3. Type scale

System sans (humanist face TBD). Material 3 type roles with two
deviations:

- Headlines have negative tracking (`-0.6 sp` on `headlineLarge`).
- Label sizes have positive tracking (`+0.2 sp` on `labelLarge`).

See `Theme.kt::KaimahiTypography` for the full table.

---

## 4. Motion

- **Standard** — 200 ms ease-out for state changes.
- **Emphasized** — 320 ms for screen transitions.
- **Streaming** — brand gradient drifts left-to-right at 5 s/cycle.
- Reduced-motion respects OS setting; gradient swaps to a static shimmer.

---

## 5. Composables

| Composable | Role | File |
| --- | --- | --- |
| `KaimahiTheme` | Wraps content in Material3 + extended tokens. | `Theme.kt` |
| `LocalKaimahiColors` | CompositionLocal for tokens not in M3. | `Theme.kt` |
| `KaimahiTokens.colors` | Read-only accessor inside composables. | `Theme.kt` |
| `AppScreen` | Canonical 5-slot page scaffold. | `AppScreen.kt` |
| `KaimahiLogo` | Canvas-drawn brand mark (3 styles). | `KaimahiLogo.kt` |
| `KaimahiBadge` | Status pill for Cloud/Local/Dual/Memory/Delegate. | `KaimahiBadge.kt` |

Use these. Don't roll new scaffolds; extend `AppScreen` if it's
missing a slot.

---

## 6. Accessibility

- **Contrast** — AA minimum (4.5:1) for body text, AAA (7:1) where
  feasible on text-strong. Pounamu (`#4FA3A3`) on `surface-1` clears
  AA for body and AAA for titles.
- **Touch targets** — 44dp minimum, 48dp default. Badges expand the
  hit area beyond their visible footprint.
- **Reduced motion** — see §4.
- **Talkback** — every status pill announces its kind + label.

---

## 7. What's reserved

- The brand gradient is a scarce resource. Three call sites in the app
  total. If a fourth wants it, push back on the design or remove one
  of the existing sites.
- Pure red is reserved — use coral (`#E07A6B`) for errors. The single
  exception is system notifications that follow OS theming.
- No accent colours outside this palette. If you need a new one,
  update `BRAND.md` first, then add to `Theme.kt`.
