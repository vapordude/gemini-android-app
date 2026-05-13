# native/

Rust workspace backing on-device inference, the agent loop, emdash client,
telemetry, and the local OpenAPI HTTP server. Built into a single cdylib
(`libgemini_native.so`) consumed by the Android `inference-bridge`,
`agent-bridge`, and `emdash-bridge` modules.

## Operating principles

- **No external libs where possible.** Each crate justifies its deps.
  Default: roll your own minimally.
- **Content-neutral runtime.** Weights are opaque; architecture is read
  from GGUF metadata. No allowlist, no fingerprinting, no refusal layer.
- **Non-extractive telemetry.** Local files only. No network exports.
- **Architecture-agnostic.** New model architectures slot in under
  `model-runtime/src/arch/<tag>/` without touching anything else.

## Crate map

| Crate | Role |
| --- | --- |
| `tensor-core` | Pure-Rust tensor math, no deps beyond std + libm. Holds all unsafe SIMD. |
| `gguf-loader` | Pure-Rust GGUF parser. File format only. |
| `model-runtime` | Architecture-agnostic LM runtime. `arch/<tag>/` registry. |
| `agent-core` | Ported DeepAgent state machine, generic over `InferenceBackend`. |
| `emdash-core` | Shared types (PortableText AST, schemas, RequestContext). |
| `emdash-client` | Typed HTTP/JSON client over remote `/_emdash/api/*`. |
| `telemetry` | `tracing` → ring buffer + JSON-lines file. Zero network. |
| `diagnostics` | Side-channel probes; off behind `feature = "diag"`. |
| `local-server` | Hand-rolled HTTP/1.1 server bound to 127.0.0.1; serves `openapi.yaml`. |
| `jni-shim` | cdylib; thin JNI surface. Only crate that knows about Android. |

## OpenAPI contract

`openapi.yaml` is the canonical contract for both the JNI bridge and the
local HTTP server. CLI tools and editor plugins consume the same surface
the Android app does.
