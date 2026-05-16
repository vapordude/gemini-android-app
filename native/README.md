# native/

Rust workspace backing on-device inference, the agent loop, emdash
management, telemetry, and the local OpenAPI HTTP server. Builds into
a single cdylib (`libgemini_native.so`) consumed by the Android
`inference-bridge`, `agent-bridge`, and `emdash-bridge` modules. Also
builds as a standalone binary that serves the same OpenAPI contract
on 127.0.0.1.

## Operating principles

- **No external libs where possible.** Each crate justifies its deps.
  Default: hand-roll. `tensor-core` allowlists only `std`.
- **Content-neutral runtime.** Weights are opaque; architecture is read
  from GGUF metadata. No allowlist, no fingerprinting, no refusal.
- **Non-extractive telemetry.** Local files only, typed enum fields.
- **Architecture-agnostic.** New families and architectures slot in
  under `model-runtime/src/arch/<family>/<tag>/` without touching
  unrelated code.

## Crate map

| Crate | Role |
| --- | --- |
| `tensor-core` | Pure-Rust tensor math + `Delegate` trait for NPU/GPU. Only crate with unsafe SIMD. |
| `gguf-loader` | Pure-Rust GGUF v3 parser. Tokenizer, per-tensor type, arch params all from metadata. |
| `model-runtime` | Architecture-agnostic LM + image runtime. `arch/lm/gemma4` is the only LM today — Gemma 4 E2B / E4B ported from `llama.cpp/src/models/gemma4.cpp`. `arch/lm/`, `arch/diffusion/` families. |
| `agent-core` | Ported DeepAgent marker-driven state machine, generic over `InferenceBackend`. |
| `emdash-core` | Shared types (PortableText AST, schemas, RequestContext). |
| `emdash-client` | Typed HTTP/JSON client over remote `/_emdash/api/*`. |
| `telemetry` | Local-only ring buffer + JSON-lines file sink. |
| `diagnostics` | Side-channel probes (D1..D11); off behind `feature = "diag"`. |
| `local-server` | Hand-rolled HTTP/1.1 on 127.0.0.1; serves the OpenAPI contract. |
| `jni-shim` | cdylib; only crate that knows about Android/JNI. |

## OpenAPI contract

`openapi.yaml` is the canonical contract. Both the JNI bridge and the
local HTTP server implement it. See `../docs/API.md` for the surface
overview.

Tags:
- `native` — canonical NDJSON surface for new clients.
- `openai-compat` — `/v1/chat/completions` etc., drop-in for existing tools.
- `health` — `/healthz`, `/readyz`, `/metrics` (Prom-style).
- `diagnostics` — `/diag/probes`, `/diag/snapshot` (only in `--features diag` builds).
- `telemetry` — `/traces` (always on, non-extractive).
- `emdash` — local proxy to remote emdash-rs instances.

## Adding a new model architecture

1. Drop a module under `model-runtime/src/arch/<family>/<your_tag>/`.
2. Implement `LanguageModel` or `ImageModel`.
3. Read every config field from GGUF metadata — never guess.
4. Add a dispatch arm in `model-runtime/src/lib.rs::load`.

The rest of the system (agent loop, tokenizer, telemetry, diagnostics,
JNI bridge, OpenAPI surface) is architecture-agnostic.

## Adding a vendor delegate (NPU / GPU)

1. New crate `native/delegate-<vendor>/` behind a cargo feature.
2. Implement `tensor_core::Delegate`, advertise `DelegateCaps`.
3. Register via a feature flag in `jni-shim` (e.g. `--features delegate-qnn`).
4. CPU stays as the always-available fallback.

See `../docs/PORTING.md` for the full porting guide.

## Tests

```
cargo test --workspace --lib
```

86+ unit tests today across quant codecs (incl. Q4_K matvec parity vs
dequant+matmul), kernels, GGUF parsing, tokenizer (incl. Gemma special
tokens), agent loop with structured errors, topological + time-aware
memory, dual-backend policy, training capture, telemetry, the local
HTTP server, and diagnostics.
