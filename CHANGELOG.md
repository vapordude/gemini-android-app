# Changelog

All notable changes to Kaimahi land here. Versions follow
[Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`. The
OpenAPI spec at `native/openapi.yaml` has its own `info.version` that
ratchets independently and is currently `0.2.0`.

## [Unreleased]

_Nothing pending. The 0.2.0 cut covers the current state of `main`._

## [0.2.0] — 2026-05-16

The Gemini-CLI + real-Gemma-inference cut. Two big shifts:

1. The "implement local inference" stub gets replaced by a working
   forward pass — GGUF → dequant → SwiGLU decoder → sampled tokens via
   JNI callback. Shape-correct; parity-unverified (see README "Known
   gaps").
2. Login stops asking for an API key. You either reuse `gemini-cli`'s
   `oauth_creds.json` (which routes through `cloudcode-pa.googleapis.com`,
   the Code Assist endpoint, with refresh + onboarding handled inside
   `CodeAssistSession`), continue with Google Sign-In, or skip auth
   entirely and stay on-device.

### Added

- **Native Q4_K_M dequantization** in `tensor-core/quant/q4_k.rs`,
  matching llama.cpp's `get_scale_min_k4` layout. Plus a single
  `dequantize_to_f32(DequantType, src, numel)` router covering F32 /
  F16 / BF16 / Q8_0 / Q4_0 / Q4_K. Mapped to the GGUF type enum.
- **`gguf_loader::TensorInfo::byte_size`** for every GGML quant family
  plus a `GgufBytes` that reads the whole file once and hands out
  `tensor_bytes(name)` slices — the loader's input shape for the
  dequant router.
- **Real `Gemma4Model::forward`** in `model-runtime/arch/lm/gemma4.rs`
  — per-layer RMSNorm → Q/K/V projections → RoPE → grouped-query SDPA
  (sliding window honoured when the metadata declares one) → O proj →
  optional Gemma 2/3 post-attn-norm → residual → SwiGLU FFN +
  optional post-ffn-norm → residual → final norm + lm_head matvec.
  Per-layer KV cache lives on the model.
- **Sampler** in the same crate: greedy `argmax`, temperature + top-k
  with a deterministic xorshift RNG seedable per request.
- **JNI session API** in `jni-shim`: `nativeOpenSession(path)`,
  `nativeGenerate(handle, prompt, TokenCallback, maxTokens, temp,
  topK, seed)`, `nativeResetSession`, `nativeCloseSession`,
  `nativeLastError`, `nativeProbe`. `nativeGenerate` resolves
  `TokenCallback.onToken(String)` by signature and calls it per
  generated piece.
- **Kotlin streaming inference**: `RustInferenceEngine.generate()` is a
  real `callbackFlow` over the JNI callback. Load / close manage the
  native session handle across model swaps.
- **`GenerateRequest.topK` + `seed`** on the domain side, so the
  ViewModel can drive the sampler.
- **`CodeAssistSession`** in `core-bridge/codeassist`: wraps the
  existing `CodeAssistClient` with token bookkeeping. Runs
  `loadCodeAssist` + `onboardUser` on first use, persists the resolved
  `cloudaicompanionProject` to `SecurePrefs.codeAssistProjectId`,
  refreshes the access token via `GeminiCliAuthService` on 401, and
  exposes `streamGenerateContent` / `countTokens`.
- **Login screen — "Load from Termux ~/.gemini"** path that cats
  `~/.gemini/oauth_creds.json` via the existing Termux bridge, parses
  `access_token` / `refresh_token` / `expiry_date`, and feeds the
  config into `RestGeminiCore.init()`.
- **Local-first cold start**: `MainActivity` checks
  `prefs.localModelPath` first; if present, it skips the login screen
  and brings the chat up in `LOCAL_AGENT` mode. Same flow used by the
  "Use local-only mode" button on the login screen.

### Changed

- **`RestGeminiCore.init`** now detects Code Assist mode (refresh
  token in the config, or persisted `useCodeAssist`) and routes
  through `CodeAssistSession` instead of the public Gemini API.
- **`RestGeminiCore.streamOnce`** branches on the active transport:
  Code Assist path uses `CodeAssistClient.streamGenerateContent`,
  public API path keeps the original SSE loop. SSE chunk parsing got
  pulled into a shared `consumeChunk` helper so both feed the same
  UI events.
- **`SecurePrefs`** now persists `refreshToken`, `tokenExpiryMs`,
  `codeAssistProjectId`, and a `useCodeAssist` flag so the auto-login
  path can resume the Code Assist session on cold start.
- **`GeminiCliAuthService`** moved from `:app/ui/login/` to
  `:core-bridge/auth/`. AppAuth dependency moved with it so the
  refresh path doesn't need the UI module.
- **Login screen** dropped the API-key tab. Three cards now: Termux
  credentials, Google sign-in, local-only mode.
- **CI** — `cargo clippy --workspace --all-targets -- -D warnings`
  passes; `cargo fmt --check` clean; `cargo test --workspace --lib`
  green (38+ tensor-core tests including new Q4_K + router tests, 7
  model-runtime sampler tests).

### Removed

- The "Enter Gemini API Key" tab on the login screen. The corresponding
  string resources (`login_tab_api`, `login_api_key_label`,
  `login_api_continue`, `login_tab_google`) are gone. The Settings →
  Account section still links out to AI Studio for users who want to
  paste a key into a config file, but there's no in-app input field.
- Old stubbed `RustInferenceEngine.generate()` that emitted a single
  empty `Token` regardless of prompt.

### Known gaps (intentional)

- **Gemma forward-pass parity is unverified.** Math wiring is real;
  output coherence against HuggingFace Gemma weights hasn't been
  ground-truthed. Gemma 3's alternating-window attention and per-layer
  RoPE base are currently uniform.
- **F32 weight storage at load.** A 2B Gemma dequantized to F32 needs
  ~8 GB RAM. Stick to ≤1B parameter models on phones until the
  quantized matvec kernel lands.
- **Local inference is text-only.** No vision encoder is wired
  on-device; image attachments only work on the cloud path. The local
  agent also doesn't drive tool calls yet.
- **Code Assist response shape** is pinned to the documented gemini-cli
  payload. If Google changes it server-side the chat throws "Code
  Assist HTTP …" and needs a request-shape patch.

[0.2.0]: https://github.com/vapordude/gemini-android-app/releases/tag/v0.2.0

## [0.1.0] — 2026-05-13

Initial consolidated release. Three upstream OSS projects folded into
one shape: the original `gemini-android-app` (aciderix), DeepAgent,
and the EmDash CMS port. Brought up under the name **Kaimahi**
(Te Reo Māori for "worker") with a privacy stance codified in the
type system and a full visual identity.

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

### Notes / known gaps (intentional, at the time of this release)
- `arch/lm/gemma4/forward` was a structural stub returning zero logits.
  **0.2.0 replaces this with a real forward pass.**
- `CloudGeminiBackend.complete` returns the existing single-shot
  result; collating streamed events into one completion per turn is
  the operator's stitching call.

### Canonical UI surface
- "The API is the schema." Per-typed-shape Compose renderers under
  `ui-components/canonical/` — `AgentTranscript`, `RuntimeInfoPanel`,
  `TraceList`, `EmdashDiffView` — exhaustive over each sealed domain
  type. No parallel UI schema; the agent's typed events ARE the form
  it fills out.
- `domain.Hint(tone, emphasis)` is an optional field on every
  `AgentEvent` variant. Lets the agent shape presentation among the
  existing tokens (ngahere / amber / pounamu / kowhai / coral × subtle
  / normal / strong) without introducing new visuals. Brand stays
  bounded; agent gets expressivity within it.

### Operator-facing scaffolding
- `KaimahiAgentDefaults.build(context, cloud, local, ...)` returns
  the canonical wiring (MultiBackend + memory dir + training dir) as
  a typed bag. Three lines instead of twenty.
- `CloudGeminiBackend` adapts the existing Google Gemini REST client
  into `InferenceBackend` so it composes with the local LM under one
  `MultiBackend`.
- `LocalLmBackend` does the symmetric thing for the on-device runtime.
- `Agent::run` in Rust now calls `MemoryStore::fold()` on
  `[FOLD_THOUGHT]`. Test asserts the wiring fires.

### Pre-history

Before this release, Kaimahi was an in-flight fork. See
[`MIHI.md`](MIHI.md) for full attribution.

[Unreleased]: https://github.com/vapordude/gemini-android-app/compare/v0.2.0...HEAD
[0.1.0]: https://github.com/vapordude/gemini-android-app/releases/tag/v0.1.0
