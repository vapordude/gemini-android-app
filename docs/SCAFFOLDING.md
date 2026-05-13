# Scaffolding on top of Kaimahi

Kaimahi is meant to be forked. Every seam is a trait, every architecture
is plug-in, the UI scaffold is a single `AppScreen` slot, and the entire
local API is one OpenAPI 3.1 file. This guide walks the common
extension points.

## Top-level orientation

```
gemini-android-app/
├── app/                  Compose UI + composition root
├── core-bridge/          cloud Gemini REST client + tool registry
├── domain/               pure types (no Android)
├── ui-components/        Theme + AppScreen + KaimahiLogo + KaimahiBadge
├── inference-bridge/     JNI facade for the native LM runtime
├── agent-bridge/         JNI facade for the agent loop
├── emdash-bridge/        HTTP client + Compose screens for remote emdash
└── native/               Rust workspace — tensor math, GGUF, agent core,
                          telemetry, diagnostics, local HTTP server
```

Read order if you're new: `MIHI.md` → `README.md` →
`docs/AGENTIC.md` → `docs/API.md` → `docs/PORTING.md` →
`docs/BRAND.md` → `docs/SCREENS.md` → here.

## Six common scaffolds

### 1. Add a new screen

`docs/SCREENS.md` is the canonical guide. Short version:

```kotlin
@Composable
fun MyScreen(onBack: () -> Unit) {
    AppScreen(
        title = "My screen",
        navigationIcon = { IconButton(onClick = onBack) { Icon(...) } },
        actions = { IconButton(onClick = { /* refresh */ }) { Icon(...) } },
    ) {
        // body — already inside a ColumnScope with vertical scroll
    }
}
```

Theme tokens come from `KaimahiTokens.colors` and the surrounding
`KaimahiTheme`. Never hard-code colors.

### 2. Add a new tool

The agent's tool registry lives in `core-bridge`. Each tool is a class
implementing `Tool`:

```kotlin
class MyTool(private val workspace: Workspace) : Tool {
    override val spec = ToolSpec(
        name = "my_tool",
        description = "What it does, one sentence.",
        category = ToolCategory.FILES,
        destructive = false,
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf("path" to mapOf("type" to "string")),
            "required" to listOf("path"),
        ),
    )

    override suspend fun execute(call: ToolCall): ToolCallResult {
        // ...
    }
}
```

Register it in `RestGeminiCore` next to the existing nine tools. Both
the cloud path and the local agent loop share this registry.

### 3. Add a new model architecture

`docs/PORTING.md §"Adding a new language-model architecture"`. Short
version: drop a module under
`native/model-runtime/src/arch/<family>/<your_tag>/`, implement
`LanguageModel` (or `ImageModel`), add a dispatch arm in
`model-runtime/src/lib.rs::load`. Architecture comes from GGUF
metadata; no allowlist, no fingerprinting.

### 4. Add a vendor NPU/GPU delegate

`docs/PORTING.md §"Adding a vendor delegate"`. New crate
`native/delegate-<vendor>/`, implement `tensor_core::Delegate`,
feature-gate so default builds stay dep-free.

### 5. Add a new HTTP endpoint

`docs/API.md §"Adding a new endpoint"`. Edit `native/openapi.yaml`, add
a route in `native/local-server/src/lib.rs`, mirror in `jni-shim` if
the Android app needs typed access. The OpenAPI contract is the
canonical source of truth — clients consume the spec, not the code.

### 6. Add a new backend (cloud or experimental local)

Implement `agent_core::InferenceBackend`:

```rust
pub trait InferenceBackend: Send {
    fn complete(&mut self, prompt: &str, stop: &[&str]) -> Result<String, String>;
}
```

Drop it into a `MultiBackend` next to the existing cloud + local
engines. The agent loop doesn't care which engine drives a turn.

## Three integration patterns worth knowing

### Dual-agent capture for local-model fine-tuning

The most powerful pattern Kaimahi enables: capture cloud + local
responses to the same prompt, then fine-tune the local model on the
cloud's trajectories. End-to-end:

1. `Settings → Training → Enable capture` swaps in `FileCapture`.
2. Run normal agent sessions. The runtime records
   `(prompt, cloud_response, local_response, accepted, latencies)`
   tuples to `filesDir/training/<session>.jsonl`.
3. Export via SAF picker.
4. Off-device, run your usual SFT / DPO / IPO pipeline on the corpus.
5. Replace the GGUF in `filesDir/models/` with the fine-tune.
6. The local model is now closer to the cloud agent's behaviour — on
   *your* prompts, on *your* tasks.

Full design in `docs/AGENTIC.md`.

### Local OpenAPI as an editor/CLI target

Point any OpenAI-compatible tool at the local server:

```bash
export OPENAI_BASE_URL=http://127.0.0.1:$(cat $XDG_RUNTIME_DIR/kaimahi-runtime/port)/v1
export OPENAI_API_KEY=ignored
```

Works with: OpenAI Python SDK, LangChain, llm (Simon Willison), Continue.dev,
Cursor, Aider, and any other client that respects `OPENAI_BASE_URL`.

The native NDJSON surface (`/info`, `/generate`, `/agent/run`) is
preferred for new tooling; the OpenAI-compat surface is for retrofits.

### Embedded emdash for site management

Kaimahi includes a typed client for [emdash-rs](https://github.com/vapordude/emdash-cai)
(content CMS). Talk to a remote emdash instance over `/_emdash/api/*`:

1. Configure a profile in `Settings → Emdash → Add instance`.
2. The local agent can now use tools like `emdash.list_collections`,
   `emdash.preview_diff`, `emdash.apply` (gated by approval).
3. Site changes drafted on-device, applied to remote.

## What to NOT change without thinking

These are load-bearing constraints; touch them on purpose, not by
accident:

- **No third-party inference libraries** in `tensor-core`. The runtime
  is dep-free for a reason: portability across silicon, license
  cleanliness, and the ability to reason about every cycle in the hot
  path. If you need linalg, vendor a small crate and gate it under a
  feature flag.
- **No content-based weight rejection** in `model-runtime`. Architecture
  is detected from metadata; weight content is opaque. Use-policy lives
  above the runtime (tools, approvals, deployment configs).
- **No network traffic from `telemetry`.** Traces are local-only,
  user-readable, user-deletable. CI enforces this via `audit_no_pii`.
- **No PII in `telemetry::Event` fields.** Telemetry is typed enums and
  scalars — never user prompts, completions, or payloads. Training
  capture (which DOES contain user data) is a separate, opt-in path.

## Quick check before you push

```
./gradlew :app:assembleDebug                            # Android build
cargo test --manifest-path native/Cargo.toml --workspace # Rust tests
cargo clippy --manifest-path native/Cargo.toml \
  --workspace --all-targets -- -D warnings              # Lint
cargo fmt --manifest-path native/Cargo.toml --all -- --check
```

CI runs all four on every push. The Android workflow surfaces gradle
errors as GitHub annotations on failure so they're visible without
auth — check `.github/workflows/android.yml` if you need to extend it.
