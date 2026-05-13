# Porting notes

This repository folds together three upstream open-source projects into
one Android client + Rust runtime. Each upstream stays loosely coupled
behind a trait seam so future engines can drop in without touching
unrelated code.

## Upstream projects

### 1. DeepAgent — agent harness
- **Upstream**: `RUC-NLPIR/DeepAgent` (MIT). The user's fork is
  `vapordude/DeepAgent-scar`.
- **What we use**: the Rust agent state machine (marker grammar
  `[BEGIN_TOOL_SEARCH]`, `[BEGIN_TOOL_CALL]`, `[FOLD_THOUGHT]`), tool
  registry shape, sled-backed persistent memory pattern.
- **What we drop**: the vLLM/OpenAI-compatible cloud client. The agent
  is now generic over `agent_core::InferenceBackend` so any local engine
  can drive it.
- **Lives in**: `native/agent-core/`.

### 2. emdash-rs — content + deployment management
- **Upstream design**: TypeScript EmDash CMS (Cloudflare-native).
  Rust port lives in `vapordude/emdash-cai`.
- **What we use**: shared types (PortableText AST, `RequestContext`,
  collection/schema models) and the `/_emdash/api/*` HTTP surface.
- **What we drop on Android**: the server-side crates (`emdash-server`,
  `emdash-sandbox`, `emdash-db`, `emdash-storage`). They ship as a
  standalone binary you deploy elsewhere; the app is a *management
  client* talking to them via REST.
- **Lives in**: `native/emdash-core/` (types) and
  `native/emdash-client/` (HTTP client). The full server-side workspace
  lives in the upstream repo.

### 3. Local model runtime — from-scratch tensor math
- **Upstream**: none. This is built from first principles per project
  constraints (no third-party inference deps, content-neutral, ARM64 +
  x86_64 portable). GGUF file format follows the de-facto spec used by
  llama.cpp et al.
- **Lives in**: `native/tensor-core/`, `native/gguf-loader/`,
  `native/model-runtime/`.

## Architecture-extensibility

The runtime is intentionally not Gemma-specific. Two model families live
side by side; new architectures slot in as siblings.

### Adding a new language-model architecture

1. Drop a module under `native/model-runtime/src/arch/lm/<your_tag>/`.
2. Implement `LanguageModel` (forward, reset, info).
3. Read all config from GGUF metadata — no guessing, no hardcoded
   constants.
4. Add a dispatch arm in `native/model-runtime/src/lib.rs::load` keyed
   on the `general.architecture` metadata value.
5. No other code changes required. The agent loop, tokenizer, telemetry,
   diagnostics, JNI bridge, and OpenAPI surface are all
   architecture-agnostic.

### Adding a diffusion / image-gen architecture

Same shape, under `arch/diffusion/<your_tag>/`. Implement `ImageModel`
(`step`, `decode_vae`, `info`). Tensor kernels are shared with the LM
path; a delegate that accelerates matmul/attention helps both.

### Adding your private architecture

Drop it under `arch/<family>/custom/<your_tag>/`. The `custom`
namespace is reserved for engines not yet published. Architecture
detection is purely metadata-driven — your weights declare their
identity, the runtime dispatches accordingly. No allowlist, no
fingerprinting, no policy gates inside the runtime.

## NPU / GPU acceleration

Vendor accelerators (Qualcomm Hexagon QNN, Apple Neural Engine, Mali GPU
via Vulkan, Adreno via OpenCL, etc.) plug in via the `Delegate` trait in
`tensor-core::delegate`. The defaults:

- CPU is always available (`CpuDelegate`).
- Delegate availability is a *runtime* property — the same APK runs on
  phones with and without an NPU. Initialization probes for delegates,
  picks the highest-priority one that succeeds, and falls back to CPU
  cleanly if none load.
- Delegate impls (which DO pull in vendor libs) live in their own
  crates behind cargo features, so the default build stays dep-free.

### Adding a vendor delegate

1. Create `native/delegate-<vendor>/` with the vendor's SDK as a feature-
   gated dep.
2. Implement `tensor_core::Delegate`, returning `DelegateKind::*` and
   advertising `DelegateCaps` for the ops you accelerate.
3. Register it via a feature flag in `jni-shim` (e.g.
   `--features delegate-qnn`).
4. The runtime's matmul/attention call sites already check
   `delegate.matmul_f32(...)` first; ops that return `false` fall back
   to the in-tree path.

## What stays content-neutral

The runtime never inspects weight content for policy decisions:

- No allowlist of approved checkpoints.
- No fingerprinting / hash-banning.
- No refusal classifier embedded in the runtime.
- No remote verification of any kind.

Architecture identity comes from a single metadata field
(`general.architecture`); everything else is opaque bytes the kernels
operate on. Abliterated weights, fine-tunes, your own future models —
all load identically.

Use-policy enforcement lives **above** the runtime, in the agent's tool-
approval flow and the user's deployment configs. The product surface
(prompts, tools, approvals) is where ethical use is mediated; that
surface is user-controlled.
