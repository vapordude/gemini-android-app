# Docs

Index of the technical documentation. Top-level docs (`README.md`,
`PRIVACY.md`, `MIHI.md`, `SECURITY.md`, `CONTRIBUTING.md`,
`CHANGELOG.md`) live one level up.

## Read in order

If you're new to the codebase:

1. [`../README.md`](../README.md) — overview, what it does today, how
   to build.
2. [`../PRIVACY.md`](../PRIVACY.md) — the four invariants. Load-bearing.
3. [`../MIHI.md`](../MIHI.md) — who we owe.
4. [`AGENTIC.md`](AGENTIC.md) — agent loop, persistent topological +
   time-aware memory, training-data capture, the failure-flow-back
   contract.
5. [`API.md`](API.md) — unified OpenAPI 3.1 surface (`native` /
   `openai-compat` / `health` / `diagnostics` / `telemetry` / `emdash`).
6. [`PORTING.md`](PORTING.md) — adding a new model architecture or
   vendor NPU/GPU delegate. Three upstream projects attribution.
7. [`BRAND.md`](BRAND.md) — voice, palette, brand mark, status
   badges, gradient policy.
8. [`SCREENS.md`](SCREENS.md) — `AppScreen` canonical shape, when to
   add a slot.
9. [`STYLES.md`](STYLES.md) — per-token technical reference, pointing
   at `BRAND.md` for the *why*.
10. [`SCAFFOLDING.md`](SCAFFOLDING.md) — six common extension scaffolds
    for forkers.
11. [`../CONTRIBUTING.md`](../CONTRIBUTING.md) — the five contracts
    contributors can't break without a conversation.
12. [`../SECURITY.md`](../SECURITY.md) — reporting path + scope.
13. [`../CHANGELOG.md`](../CHANGELOG.md) — what's shipped.

## Read by task

| You want to… | Start at |
| --- | --- |
| Add a new screen | [`SCREENS.md`](SCREENS.md) |
| Add a new tool to the agent | [`SCAFFOLDING.md`](SCAFFOLDING.md) §"Add a new tool" |
| Add a new model architecture | [`PORTING.md`](PORTING.md) |
| Add an NPU/GPU vendor delegate | [`PORTING.md`](PORTING.md) §"NPU / GPU acceleration" |
| Add a new HTTP endpoint | [`API.md`](API.md) §"Adding a new endpoint" |
| Wire a new inference backend | [`SCAFFOLDING.md`](SCAFFOLDING.md) §"Add a new backend" |
| Fine-tune the local model with dual-agent capture | [`AGENTIC.md`](AGENTIC.md) §"Training-data capture" |
| Change a color or token | [`BRAND.md`](BRAND.md) → [`STYLES.md`](STYLES.md) → `Theme.kt` |
| Report a privacy issue | [`../SECURITY.md`](../SECURITY.md) |

## Read by file

| Module / file | Doc that covers it |
| --- | --- |
| `native/tensor-core/` | [`PORTING.md`](PORTING.md), [`SCAFFOLDING.md`](SCAFFOLDING.md) |
| `native/model-runtime/` | [`PORTING.md`](PORTING.md) |
| `native/agent-core/` | [`AGENTIC.md`](AGENTIC.md) |
| `native/telemetry/` | [`../PRIVACY.md`](../PRIVACY.md), [`AGENTIC.md`](AGENTIC.md) |
| `native/diagnostics/` | [`AGENTIC.md`](AGENTIC.md), inline plan in `lib.rs` |
| `native/local-server/` + `native/openapi.yaml` | [`API.md`](API.md) |
| `ui-components/` | [`BRAND.md`](BRAND.md), [`STYLES.md`](STYLES.md), [`SCREENS.md`](SCREENS.md) |
| `app/`, `core-bridge/`, bridges | [`SCAFFOLDING.md`](SCAFFOLDING.md) |
