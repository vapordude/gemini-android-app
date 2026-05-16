# Daily front page — source + topic spec

> Status: **scaffolded**. Screen + drawer route shipped; real fetcher is a
> follow-up. This doc is the source-of-truth list the fetcher will pull from.

A daily research feed for **Cathedral / Scarlet / Crimson / Lux** work — arXiv
drops, lab blogs, cognition / consciousness / quantum synthesis pieces. The
front page surfaces the highest-signal items of the last 24 hours, filtered
through topic chips and the in-repo keyword list.

## Core paper sources

### arXiv (the daily target)

- `arxiv.org/list/cs.LG/recent` — machine learning
- `arxiv.org/list/cs.AI/recent` — AI generally
- `arxiv.org/list/stat.ML/recent` — statistical ML
- `arxiv.org/list/cs.CL/recent` — computation + language
- `arxiv.org/list/quant-ph/recent` — quantum (for cognition / synthesis work)
- `arxiv.org/list/gr-qc/recent` — general relativity + quantum cosmology
- `arxiv.org/list/nlin.AO/recent` — adaptation + self-organising systems
- `arxiv.org/list/q-bio.NC/recent` — neurons + cognition

RSS feeds: `http://export.arxiv.org/rss/<category>` (e.g.
`http://export.arxiv.org/rss/cs.LG`). The export endpoint is rate-friendly and
returns Atom XML.

### Hugging Face papers

- `huggingface.co/papers` — community-upvoted, linked model cards + demos.
  Strong signal-from-noise filter on top of arXiv.

### Lab drops

Daily blog rotation (RSS where available, scraped otherwise):

- DeepMind — `deepmind.google/discover/blog`
- Meta AI (FAIR) — `ai.meta.com/blog`
- Mistral — `mistral.ai/news`
- Anthropic — `anthropic.com/news`
- xAI — `x.ai/blog`
- OpenAI — `openai.com/blog` (erratic; tech reports page worth pinging)
- EleutherAI — `blog.eleuther.ai`
- Together AI — `together.ai/blog`
- LAION — `laion.ai/blog`

### Aggregators

- Papers With Code — `paperswithcode.com` (linked to GitHub + benchmarks)
- Semantic Scholar — `semanticscholar.org` (influence graphs, follow-ups)
- The Gradient — `thegradient.pub`
- Interconnects (Substack) — `interconnects.ai`

## Cognition / consciousness / synthesis sources

### Labs

- Princeton — *Natural and Artificial Minds* initiative
- Cambridge — CHIA + Deep Cognition Lab + CAPAIBLE
- Tsinghua — AI of Brain & Cognition Center
- Georgia Tech — Cognitive Architecture Lab
- Tilburg University — CSAI + MindLabs
- Santa Fe Institute — complex systems / emergence / information theory
- Allen Institute for Brain Science — large-scale neural datasets
- VERSES AI — Karl Friston / active inference / Free Energy Principle

### Journals + venues

- *Journal of Artificial Intelligence and Consciousness* (World Scientific) —
  editor Ryota Kanai of Araya Research
- *Frontiers in Psychology* — cross-domain synthesis
- *Mind & Language* (Wiley) — philosophy-of-mind anchor
- PhilPapers — primary index
- Aeon, Nautilus — accessible-but-rigorous long-form

### Quantum + cosmology institutions

- DOE National QIS Research Centers (Brookhaven, Argonne, LBL, ORNL, Fermilab)
- QuTech (TU Delft)
- Max Planck Institute of Quantum Optics
- IQOQI Vienna (Austrian Academy of Sciences)
- John Templeton Foundation — funded interdisciplinary work
- Perimeter Institute — foundational physics, quantum-info QM

## Topic chips (in the UI)

The drawer route exposes filter chips for the major axes. The chip→source
mapping is hard-coded in `FeedSources.kt`:

| Chip | arXiv categories | Extra sources |
|---|---|---|
| **All** | union of all below | union |
| **cs.LG / ML** | cs.LG, stat.ML | HF Papers, PWC, lab blogs |
| **cs.CL / NLP** | cs.CL | HF Papers, lab blogs |
| **quant-ph** | quant-ph | Max Planck QO, QuTech, DOE QIS |
| **cosmology** | gr-qc | Perimeter, Templeton |
| **cogsci** | q-bio.NC, nlin.AO | Princeton NAM, CHIA, VERSES AI |
| **synthesis** | (cross-cut) | SFI, Allen Inst., JAIC, Nautilus |
| **labs** | — | DeepMind / FAIR / Mistral / Anthropic / xAI |

## High-yield search terms

Bound into `FeedKeywords.kt` so the fetcher can tag and rank.

### Architecture / training

state space models · mamba · linear attention · ternary networks · bitmamba ·
speculative decoding · quantization aware training · GGUF · KV cache
compression · mixture of experts · edge inference · ONNX optimization · SIMD
kernels · sub-quadratic attention · hybrid SSM-transformer · rotary position
embedding · Lyapunov stability training · loss landscape · rank collapse

### Cognition / consciousness

integrated information theory · free energy principle · active inference ·
quantum cognition · orchestrated objective reduction · panpsychism
computational · morphic resonance · emergence and consciousness · quantum
biology · information ontology · cosmic fine-tuning · anthropic principle ·
holographic principle cognition · entropic gravity · causal emergence ·
predictive processing · attractor dynamics cognition · topological field
theory mind · quantum darwinism

### Fringe-but-rigorous

biosemiotics · autopoiesis · enactivism · neurophenomenology

## Implementation plan

### v1 (this branch) — scaffolded

- `app/ui/research/DailyFrontPageScreen.kt` — Compose surface
- `app/ui/research/DailyFrontPageViewModel.kt` — state holder
- `core-bridge/research/FeedSources.kt` — typed source/topic config
- `core-bridge/research/FeedKeywords.kt` — typed keyword tags
- Drawer destination added to `KaimahiDestination`
- Empty state + topic chips render; "Fetcher coming soon" footer card

### v2 — Kotlin RSS fetcher

- Pure-Kotlin Atom/RSS parser in `core-bridge/research/RssFetcher.kt`
- arXiv-only first (8 categories, 1 endpoint shape)
- Background refresh on app foreground; cache to JSON under
  `filesDir/research/feed.json` so the screen renders offline

### v3 — full source coverage

- Lab blogs added via Atom/RSS where available
- HF Papers + Papers With Code via JSON API
- Per-source ranking + per-keyword tagging

### v4 — sovereign / agent-aware

- Local Gemma agent summarises each item ("worth your time, ignore, save")
- Agent can pin items to a project as memory entries
- The keyword list lives in user-editable config; the agent can suggest
  additions based on what the operator reads
