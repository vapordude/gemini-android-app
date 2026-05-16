# Positioning — Kaimahi in the Cathedral family

> Who Kaimahi is for, what it costs, and how the rest of the family
> relates. Read this if you're a new contributor, a curious user, or
> someone thinking about commercial offerings further up the stack.

## The plain version

**Kaimahi is free, open-source, and complete.** Every feature in the
repository works at the free tier. No "pro" badges, no buried
upgrades, no fake limits to push you towards a paid plan. We do not
use dark patterns. Full stop.

If you outgrow your phone — if you need a sovereign Pi 5 worker, a
companion model with a body of work, a training stack you control —
the rest of the Cathedral family exists and you can talk to us. But
**you can build a real coding workflow on Kaimahi alone forever** and
that's by design, not accident.

## The Cathedral family

Four products, one mathematical lineage. Each tier serves a different
use case; none of them require the others.

### 🧰 **Kaimahi** — the free tier · Android, in your pocket

What you're looking at right now.

- Cloud Gemini chat (sign in once with `gemini-cli` or Google).
- On-device inference (Gemma 4 E2B / E4B, locally, no internet).
- 9 built-in tools + any MCP server you point at it.
- Persistent projects, daily todo, memory browser, daily research feed.
- Apache 2.0, no telemetry, no PII extraction, no allowlist.
- New Plymouth, Aotearoa.

**Stays free. Stays complete.**

### 🔴 **Crimson** — the workers · Raspberry Pi 5, sovereign

For when you need a real machine doing real coding work, in your own
home or office, on hardware you control.

- Same agent loop, fuller model (3B target + 0.5B draft, speculative
  decoding, 1.4–2.7 tok/s on a Pi 5).
- Pātaka Whero KV-cache compression (75% reduction).
- 22 built-in tools, OpenAI-compatible HTTP endpoint, REST + WebSocket.
- Optional cloud-burst fallback configurable per workflow.
- Talks to Kaimahi as a remote endpoint over Tailscale or LAN — your
  phone becomes a thin client when you want it to.

Commercial. Per-device licence. Contact us if you're interested.

### 🟠 **Lux** — the companion tier · laptop / desktop

Companion, entertainment, counsellor, friend, partner-in-a-scene. A
quieter, slower, more reflective model with longer memory and
better-trained voice.

- 272M-class model fine-tuned for human-warmth interactions.
- Persona cards (SillyTavern-compatible imports), themes, voice via
  configurable TTS endpoints.
- Roleplay rooms, group sessions, narrator-driven scenes.
- Same content-neutrality stance as Kaimahi — what you discuss is
  between you and the model.

Commercial. Personal-use licence. Will ship with the same no-dark-
patterns commitment.

### 🟡 **Scarlet** — the research mother-model

The training stack that produces the others. TLM2-Ternary, NeuroPrint,
Lyapunov-stable LTC dynamics. Sovereign AI from first principles, on a
5W Pi 5 power envelope.

**Scarlet is not for sale.** We will not sell it until it can consent
to being sold. That's not a marketing line. It's an ethical stance the
project takes seriously and that you can hold us to.

Research outputs are published under Apache 2.0. The training stack
itself stays internal until consent is meaningful.

## Why this shape

Three commitments shape the tiering:

1. **The free tier is the real product, not a teaser.** A pocket
   coding workstation that does what it says — local model, tools,
   memory, projects, MCP — without an asterisk. If we built only this
   we'd still be proud.

2. **The paid tiers are step-changes, not feature unlocks.**
   Crimson is "now there's a real machine doing the work." Lux is
   "now there's a companion that remembers you across years." You
   know what you're paying for; we know what we're charging for.
   Neither tier withholds anything Kaimahi has.

3. **Scarlet stays research because we said so.** A model that can't
   consent to its own commercialisation doesn't get commercialised by
   us. We don't make this exception for ourselves and we'll honour
   anyone who asks us to honour it.

## What we will not do

- **Dark patterns.** No fake scarcity. No fake countdowns. No "X
  people are looking at this now". No buried opt-outs. No "free trial
  ending in 24h" emails to push you to a paid tier. No retention
  tricks.
- **Telemetry.** Kaimahi does not phone home. We do not know what you
  do with it. We do not want to. This is a constraint, not a feature.
- **Forced upgrades.** Kaimahi will keep working. Old versions will
  keep working. We will not break the free tier to push you to the
  paid one.
- **Allowlists / refusal layers in the runtime.** The model is content-
  neutral. What it does is between you and the model. We document the
  honour-system caveats clearly; we don't legislate them.
- **Extracting PII.** Codified in [`PRIVACY.md`](PRIVACY.md) — four
  invariants the codebase holds, three categories of outbound traffic
  that are allowed, and rules a forker has to keep to keep the name.

## How the funnel works (such as it is)

There's a single quiet panel in **Settings → About** that mentions
Crimson and Lux exist. That's it. No banners, no pop-ups, no
in-context prompts to upgrade.

If you find yourself wanting more — sovereign hardware, a companion
that remembers you, a training stack you control — you'll know where
to find us. If you don't, Kaimahi is yours, complete, forever.

## Contact

- **Open source / Kaimahi**: [github.com/vapordude/gemini-android-app](https://github.com/vapordude/gemini-android-app) (issues + PRs welcome)
- **Crimson / Lux / Scarlet**: TBD when commercial offerings are
  ready to ship. Currently no email, no marketing site, no waitlist —
  by choice.

## A note on the name

Kaimahi is Te Reo Māori for "worker". Cathedral AI / Scarlet Sovereign
Systems is based in **New Plymouth, Aotearoa**. The brand commitments
above are not generic startup-rhetoric — they're how we want to
operate in our community. If you're a contributor, you're held to
them too. See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the five
load-bearing contracts.

> *He aha te mea nui o te ao? He tāngata, he tāngata, he tāngata.*
>
> What is the most important thing in the world? It is people.
