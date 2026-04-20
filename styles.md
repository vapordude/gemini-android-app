# Gemini for Android — Visual System

Faithful to **aistudio.google.com** (dark first) and the 2025 Gemini /
Google brand refresh. Tokens below are the single source of truth; the
Compose theme (`ui-components/.../Theme.kt`) and Android XML resources
mirror them one-for-one.

Design language: Material 3, dark-first, low chroma, thin strokes, tight
type. Accent hue is Google Blue 300 (`#8AB4F8`) — same as the selection
glow used across Gemini / Gmail / Drive dark mode. A brand gradient is
reserved for spark/hero moments.

---

## 1. Color tokens

### 1.1 Surfaces (dark, primary)

```css
:root {
  /* Anchored on AI Studio's stacked surface scale.                       */
  /* Each step adds ~+4–8 L* over the previous, with no tint shift.       */
  --ais-bg:            #131314; /* app canvas (body) */
  --ais-surface:       #1E1F20; /* cards, top app bar, drawer */
  --ais-surface-hi:    #282A2C; /* raised elements, sticky input */
  --ais-surface-max:   #35373A; /* hover / pressed on raised surfaces */
  --ais-scrim:         rgba(0, 0, 0, 0.55);

  /* Subtle overlays reused for glass panels (hero, approval card).       */
  --ais-panel:         rgba(30, 31, 32, 0.72);
  --ais-panel-solid:   #1E1F20;
}
```

### 1.2 Lines & strokes

```css
:root {
  --ais-line:          #2F3133; /* divider, card border */
  --ais-line-strong:   #3C3D3F; /* input outline, focused dividers */
  --ais-focus-ring:    #8AB4F8; /* 2px outline on keyboard focus */
}
```

### 1.3 Text

```css
:root {
  --ais-text:          #E3E3E3; /* primary */
  --ais-text-strong:   #F5F5F5; /* hero titles */
  --ais-text-secondary:#C4C7C5; /* body, dense tables */
  --ais-muted:         #9AA0A6; /* metadata, help text */
  --ais-disabled:      #5F6368; /* disabled labels */
}
```

### 1.4 Interactive — chips / states

```css
:root {
  --ais-chip:          #2A2B2F;
  --ais-chip-hover:    #34363A;
  --ais-chip-selected: rgba(138, 180, 248, 0.14); /* blue-300 @ 14% */
  --ais-chip-on-selected: #D3E3FD;

  /* Pointer-state layers (Material 3 state layer formula over surface).  */
  --ais-state-hover:   rgba(227, 227, 227, 0.08);
  --ais-state-press:   rgba(227, 227, 227, 0.12);
  --ais-state-drag:    rgba(227, 227, 227, 0.16);
}
```

### 1.5 Accent (brand)

Google Blue 300 is the interactive accent in every Google dark product
(AI Studio included). Use the full brand gradient only for the spark/hero.

```css
:root {
  /* Primary — used on FABs, links, selection, progress. */
  --ais-accent:        #8AB4F8; /* Google Blue 300 */
  --ais-on-accent:     #062E6F; /* Blue 900 */
  --ais-accent-muted:  #1A3A73; /* container, badge bg */
  --ais-on-accent-muted:#D3E3FD;

  /* Secondary / tertiary — used sparingly (tool chips, code accents).   */
  --ais-teal:          #78D9EC;
  --ais-violet:        #C58AF9;

  /* Gemini four-color spark (2025 refresh, Google primaries).           */
  --ais-spark-blue:    #4285F4;
  --ais-spark-red:     #EA4335;
  --ais-spark-yellow:  #FBBC05;
  --ais-spark-green:   #34A853;
}

/* Official brand gradient — used for the spark logo, hero accents,
 * streaming progress bar, nothing else. Order echoes the sparkle shape. */
.gemini-spark {
  background: conic-gradient(
    from 210deg at 50% 50%,
    var(--ais-spark-blue)   0%,
    var(--ais-spark-green)  25%,
    var(--ais-spark-yellow) 55%,
    var(--ais-spark-red)    75%,
    var(--ais-spark-blue)   100%
  );
}

/* Ambient dusk gradient — used for hero halos and the "Ready when you
 * are" glow behind the launcher mark on the empty state.               */
.gemini-dusk {
  background: radial-gradient(
    120% 80% at 50% 0%,
    rgba(138, 180, 248, 0.18) 0%,
    rgba(197, 138, 249, 0.10) 30%,
    transparent 60%
  );
}
```

### 1.6 Semantic states (Google 300 on dark)

```css
:root {
  --ais-success:       #81C995;
  --ais-success-bg:    rgba(129, 201, 149, 0.14);
  --ais-warn:          #FDD663;
  --ais-warn-bg:       rgba(253, 214, 99, 0.14);
  --ais-danger:        #F28B82;
  --ais-danger-bg:     rgba(242, 139, 130, 0.14);
  --ais-info:          #8AB4F8;
  --ais-info-bg:       rgba(138, 180, 248, 0.14);
}
```

### 1.7 Light mirror

Used when the device forces light mode. Same roles, inverted luminance.

```css
@media (prefers-color-scheme: light) {
  :root {
    --ais-bg:            #FFFFFF;
    --ais-surface:       #F8F9FA;
    --ais-surface-hi:    #F1F3F4;
    --ais-surface-max:   #E8EAED;
    --ais-line:          #E8EAED;
    --ais-line-strong:   #DADCE0;
    --ais-text:          #1F1F1F;
    --ais-text-strong:   #0B0B0B;
    --ais-text-secondary:#444746;
    --ais-muted:         #5F6368;
    --ais-disabled:      #9AA0A6;
    --ais-accent:        #0B57D0; /* Blue 700 */
    --ais-on-accent:     #FFFFFF;
    --ais-accent-muted:  #D3E3FD;
    --ais-on-accent-muted:#041E49;
    --ais-chip:          #F1F3F4;
    --ais-chip-hover:    #E8EAED;
    --ais-chip-selected: rgba(11, 87, 208, 0.12);
    --ais-chip-on-selected: #041E49;
  }
}
```

---

## 2. Shape scale

```css
:root {
  --ais-radius-none:  0;
  --ais-radius-xs:    6px;   /* small chips, tag pills (dense rows) */
  --ais-radius-sm:    12px;  /* secondary chips, list rows */
  --ais-radius-md:    16px;  /* cards, dropdowns, tooltips */
  --ais-radius-lg:    20px;  /* elevated sheets, dialogs */
  --ais-radius-xl:    28px;  /* hero/media, top-level surfaces */
  --ais-radius-2xl:   36px;  /* pill buttons, chat input, CTA */
  --ais-radius-full:  9999px;/* FAB, avatar, status dot */
}
```

---

## 3. Typography

Primary stack mirrors Google's production CSS. On Android we fall back
to the system sans (Roboto) until Google Sans Flex ships with the APK.

```css
:root {
  --ais-font-display: "Google Sans Flex", "Google Sans", Inter,
                      system-ui, sans-serif;
  --ais-font-text:    "Google Sans Text", "Inter Tight", Inter,
                      system-ui, sans-serif;
  --ais-font-mono:    "Roboto Mono", "JetBrains Mono",
                      ui-monospace, SFMono-Regular, Menlo, monospace;
}

/* Scale — `clamp()` values match AI Studio landing + app shell. */
.ais-t-display   { font: 500 clamp(42px, 5vw, 72px)/0.96 var(--ais-font-display); letter-spacing: -0.04em; }
.ais-t-headline  { font: 500 clamp(28px, 3vw, 40px)/1.05 var(--ais-font-display); letter-spacing: -0.025em; }
.ais-t-title     { font: 500 22px/1.2              var(--ais-font-display); letter-spacing: -0.01em; }
.ais-t-subtitle  { font: 300 clamp(18px, 2vw, 28px)/1.2 var(--ais-font-text); }
.ais-t-body      { font: 400 15px/1.5              var(--ais-font-text); }
.ais-t-body-sm   { font: 400 13px/1.45             var(--ais-font-text); }
.ais-t-label     { font: 500 13px/1.2              var(--ais-font-text); letter-spacing: 0.1px; }
.ais-t-caption   { font: 400 12px/1.35             var(--ais-font-text); color: var(--ais-muted); }
.ais-t-code      { font: 400 13px/1.5              var(--ais-font-mono); }
```

---

## 4. Motion

```css
:root {
  --ais-motion-standard: cubic-bezier(0.2, 0.0, 0, 1.0);   /* Material M3 */
  --ais-motion-emphasized: cubic-bezier(0.3, 0.0, 0.8, 0.15);
  --ais-dur-fast:   120ms;
  --ais-dur-base:   200ms;
  --ais-dur-slow:   320ms;
}
```

Rules of thumb
- Hover → **120 ms**, standard curve.
- Expand / collapse → **200 ms**, standard.
- Enter from nothing (drawer, dialog) → **320 ms**, emphasized.
- Avoid scale bounces; prefer opacity + translateY ≤ 8px.

---

## 5. Elevation

AI Studio avoids heavy drop-shadows — it raises surfaces with
**tonal lift** (lighter `--ais-surface-*` step) and a 1 px stroke.

```css
.ais-elevation-0 { background: var(--ais-bg); }
.ais-elevation-1 { background: var(--ais-surface);    border: 1px solid var(--ais-line); }
.ais-elevation-2 { background: var(--ais-surface-hi); border: 1px solid var(--ais-line); }
.ais-elevation-3 { background: var(--ais-surface-max);border: 1px solid var(--ais-line-strong); }
/* Only the modal dialog / bottom sheet gets a real shadow, and it's soft. */
.ais-elevation-modal {
  background: var(--ais-surface-hi);
  border: 1px solid var(--ais-line-strong);
  box-shadow: 0 24px 48px rgba(0, 0, 0, 0.45);
}
```

---

## 6. Components

### 6.1 Buttons

| role        | height | radius          | bg                    | fg                    |
|-------------|--------|-----------------|-----------------------|-----------------------|
| Primary     | 40     | `--radius-2xl`  | `--ais-accent`        | `--ais-on-accent`     |
| Secondary   | 36     | `--radius-sm`   | `--ais-chip`          | `--ais-text`          |
| Tonal       | 36     | `--radius-2xl`  | `--ais-accent-muted`  | `--ais-on-accent-muted`|
| Text        | 36     | `--radius-sm`   | transparent           | `--ais-accent`        |

Focus ring: `0 0 0 2px var(--ais-focus-ring)` outside the stroke.

### 6.2 Chat input (the "pill")

- Radius `--ais-radius-2xl`; background `--ais-surface-hi`; 1 px
  `--ais-line-strong`. Focused → 1 px `--ais-accent`.
- Left affordance: `+` in 36 px icon button, transparent.
- Right affordance: 40 px FAB, `--ais-accent`, spins to `--ais-danger`
  when stop is available.

### 6.3 Chat bubbles

- User: filled `--ais-accent-muted` (or `--ais-accent` at 0.92 α) with
  `--ais-radius-lg`, right-aligned.
- Model: **no fill**, no border — just text on `--ais-bg`. Long press
  opens the actions menu. Copy/Regenerate appear on hover in a pill row.
- Tool: `--ais-surface` card with 1 px `--ais-line`, monospace preview,
  collapse/expand row. Accent dot uses the tool status color
  (info / success / danger).

### 6.4 Drawer

- Background `--ais-surface`. Selected row uses `--ais-chip-selected`
  with rounded `--ais-radius-full` pill; left content inset 16 px.
- Section labels: `.ais-t-label` in `--ais-muted`, 24 px left pad.

### 6.5 Dialogs / sheets

- `border-radius: var(--ais-radius-xl)` on the top corners (sheets) or
  all four (dialog). Background `--ais-surface-hi`, border
  `--ais-line-strong`, elevation-modal shadow.

### 6.6 Code / diff

- Font `--ais-font-mono`, 13/1.5. Background `--ais-surface`.
- `+ ` lines: bg `--ais-success-bg`, text `--ais-success`.
- `- ` lines: bg `--ais-danger-bg`, text `--ais-danger`.
- Chunk headers (`@@…`): `--ais-muted`, italic.

---

## 7. Background treatment

The landing-style grid + radial halo is acceptable **only** on the
login/empty screens. Elsewhere the canvas stays flat on `--ais-bg`.

```css
.ais-canvas-ambient {
  position: relative;
  background: var(--ais-bg);
}
.ais-canvas-ambient::before {
  content: "";
  position: absolute; inset: 0;
  pointer-events: none;
  opacity: 0.40;
  background-image:
    linear-gradient(to right,  rgba(255,255,255,0.035) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(255,255,255,0.035) 1px, transparent 1px),
    radial-gradient(circle at 20% 8%,  rgba(138,180,248,0.08), transparent 22%),
    radial-gradient(circle at 80% 14%, rgba(197,138,249,0.06), transparent 20%);
  background-size: 28px 28px, 28px 28px, 100% 100%, 100% 100%;
  mask-image: linear-gradient(180deg, #fff 0%, transparent 72%);
}
```
