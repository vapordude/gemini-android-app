# Changelog

All notable changes to Kaimahi land here. Versions follow
[Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`. The
OpenAPI spec at `native/openapi.yaml` has its own `info.version` that
ratchets independently and is currently `0.2.0`.

## [Unreleased]

### Added
- Top-level [`PRIVACY.md`](PRIVACY.md) — four invariants + outbound
  traffic table + rules for forkers.
- Top-level [`SECURITY.md`](SECURITY.md) — reporting path, scope, the
  non-extractive contract re-affirmed.
- Top-level [`CONTRIBUTING.md`](CONTRIBUTING.md) — five load-bearing
  contracts + workflow.
- Top-level [`MIHI.md`](MIHI.md) — acknowledgement of upstream forks
  (the original `gemini-android-app` by aciderix, DeepAgent,
  emdash-rs) and foundations.
- `docs/AGENTIC.md` — dual-agent harness, persistent topological +
  time-aware memory, training-data capture pattern.
- `docs/API.md` — surface walkthrough for the unified OpenAPI 3.1
  contract.
- `docs/BRAND.md` + refreshed `docs/STYLES.md` — pounamu / kowhai /
  kauri palette, brand mark, status badges, gradient policy.
- `docs/PORTING.md` — adding a new model architecture, vendor
  delegate, custom arch family.
- `docs/SCAFFOLDING.md` — six common extension scaffolds.
- `docs/SCREENS.md` — canonical `AppScreen` shape + when to add a slot.
- French `fr/README.md` rewritten to mirror the English README under
  the Kaimahi brand.

### Native Rust workspace
- `tensor-core` — pure-Rust tensor math (no `libm` dep; `f32::sqrt`
  etc are stable). Q4_0 + Q8_0 dequantizers, matmul/matvec, RMSNorm,
  softmax, SwiGLU, RoPE, SDPA with optional sliding-window mask, a
  hand-rolled row-tile scheduler, and a `Delegate` trait for vendor
  NPU/GPU hooks.
- `gguf-loader` — pure-Rust GGUF v3 parser. Full `GgmlType` enum,
  metadata + tensor index + alignment-aware data start.
- `model-runtime` — architecture-agnostic LM + image-gen runtime.
  `LanguageModel` / `ImageModel` traits, `arch/{lm,diffusion}/<tag>/`
  registry, `Gemma4Config::from_gguf` reading every parameter from
  metadata. GGUF-embedded SentencePiece tokenizer with first-class
  support for special tokens (Gemma turn markers, multimodal slots).
- `agent-core` — DeepAgent marker-driven state machine with
  structured `AgentError` flowing back into the transcript before the
  next turn ("fail alarms"). `MultiBackend` for cloud-and-local in
  one session. Topological + time-aware `MemoryStore` (5 recall
  modes). `TrainingCapture` seam for dual-agent reasoning corpus.
- `emdash-core` + `emdash-client` — shared types + hand-rolled HTTP
  client for remote emdash-rs instances.
- `telemetry` — local-only ring buffer + JSON-lines file sink.
  `Event::Error.tag: &'static str` so the compiler refuses runtime
  payload at telemetry sites.
- `diagnostics` — `probe!()` macro that compiles to `{}` outside
  `--features diag` builds. Probe call sites wired at D1 (model
  loaded), D5 (tokenizer), D6 (sampler), D8 (agent transitions).
- `local-server` — hand-rolled HTTP/1.1 on `127.0.0.1`, kernel-
  assigned port. Implements the OpenAPI contract.
- `jni-shim` — only crate that knows about Android/JNI. cdylib name
  is `libkaimahi_native`.

### Android / Kotlin
- Full rebrand: `com.gemini.*` → `nz.kaimahi.*` across all 7 modules.
  Application id `nz.kaimahi.app`. Theme `Theme.KaimahiUI`. App name
  "Kaimahi".
- New modules: `:inference-bridge`, `:agent-bridge`,
  `:emdash-bridge` (Kotlin facades over the native runtime).
- `:domain` extended with `InferenceEngine`, `AgentRuntime`,
  `ModelHandle`, `RuntimeInfo`, `TraceEvent`, `DeploymentConfig`,
  `EmdashClient`, plus a Kotlin-side `InferenceBackend` +
  `MultiBackend` mirror of the Rust side.
- `:core-bridge/CloudGeminiBackend` — adapts the existing
  `GeminiCore` cloud REST client into the `InferenceBackend` trait so
  it composes with the on-device LM under one `MultiBackend`.
- `:ui-components` — Kaimahi palette (pounamu / kowhai / kauri),
  `KaimahiTheme`, `AppScreen` 5-slot scaffold, Canvas-drawn
  `KaimahiLogo`, `KaimahiBadge` status pill.
- New Compose screens under `app/.../ui/local/`: `InferenceModeToggle`,
  `TraceViewerScreen`, `DeploymentConfigsScreen`.

### CI
- `.github/workflows/native.yml` — `cargo fmt --check` + `clippy -D
  warnings` + `cargo build --release` + `cargo test`, plus a
  cross-compile job that builds `aarch64-linux-android` and
  `x86_64-linux-android` via `cargo-ndk`.
- `.github/workflows/android.yml` extended with a "surface gradle
  errors as `::error::` annotations on failure" step so public PR
  pages show the error without log-auth.

### Test count
- **66 unit tests** across quant codecs, kernels, GGUF parsing,
  tokenizer with Gemma special tokens, agent loop with structured
  errors, topological + time-aware memory (DAG traversal, exp-decay
  recall, expiry, file round-trip), dual-backend policy, training
  capture, telemetry, local HTTP server, and diagnostics.

### Notes / known gaps (intentional)
- `arch/lm/gemma4/forward` is currently a structural stub returning
  zero logits — the operator wires their own runtime when stitching
  the final weight loader. Everything around it is wired and tested.
- `CloudGeminiBackend.complete` returns the existing single-shot
  result; collating streamed events into one completion per turn is
  the operator's stitching call.

## Pre-history

Before this changelog began, Kaimahi was an in-flight fork of three
upstream open-source projects. See [`MIHI.md`](MIHI.md) for full
attribution.
