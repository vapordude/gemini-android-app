# Local API

A single contract drives the runtime: `native/openapi.yaml` (OpenAPI
3.1). Two implementations share it.

| Caller | Transport | Where |
| --- | --- | --- |
| Android app | JNI | `inference-bridge`, `agent-bridge`, `emdash-bridge` |
| CLIs, editor plugins, devops scripts | HTTP/1.1 on 127.0.0.1 | hand-rolled in `native/local-server/` |

Everything stays on the loopback interface. There is no remote
component. There is no analytics. The OpenAI-compatible endpoints are
provided for tooling convenience only — they live alongside the native
contract, not above it.

## Surfaces

The spec groups endpoints into six tags so callers can opt into the
shape they like.

### `native` — canonical local surface

Prefer this for new clients written against this runtime. NDJSON for
streams; rich event types.

- `GET  /info` — runtime info (ISA, threads, delegate, loaded model).
- `GET  /models` — files under `filesDir/models/`.
- `POST /models/load` — load weights from a path; arch comes from GGUF metadata.
- `POST /generate` — NDJSON token stream.
- `POST /agent/run` — NDJSON agent-event stream (thinking, tool calls, messages).
- `POST /agent/decision` — resolve a pending tool-call approval.

### `openai-compat` — drop-in for existing tooling

Set `OPENAI_BASE_URL=http://127.0.0.1:<port>/v1`; any API key is accepted
(ignored locally). Streaming uses SSE per the OpenAI spec.

- `GET  /v1/models`
- `POST /v1/chat/completions` (incl. `stream`, `tools`, multimodal `image_url`)
- `POST /v1/completions` (legacy)
- `POST /v1/embeddings`
- `POST /v1/images/generations` — Stable Diffusion class, returns 501 until `arch/diffusion/<arch>` lands
- `POST /v1/images/edits` — inpaint/img2img (multipart upload)
- `POST /v1/videos/generations` — stable contract, returns 501 until a video arch ships

The spec is published now so clients can wire against it; the 501s
clearly mark what isn't implemented yet.

### `health` — probes for orchestration

- `GET /healthz` — liveness (`200 ok` if the process is alive).
- `GET /readyz` — readiness (`200` once a model is loaded + warmed up;
  `503` otherwise, with a structured reason).
- `GET /metrics` — Prometheus text format. **Counters only** —
  tokens/sec, kernel timings, ABI tier in use, tool-call counts and
  latencies. No prompts, no completions, no payloads.

### `diagnostics` — only present in `--features diag` builds

- `GET /diag/probes` — list of active probe sites (D1..D11).
- `GET /diag/snapshot?tail=N` — recent in-memory probe events.

Both return 404 in default builds so vendor tools can probe support.

### `telemetry` — always-on, non-extractive

- `GET /traces?tail=N` — read the local JSON-lines trace file.
  Typed-enum fields only; field values are never raw user input.

### `emdash` — management of remote emdash-rs instances

Local proxy with per-profile auth handling.

- `GET  /emdash/profiles` — connection profiles (URL + label; never auth).
- `GET  /emdash/{profile}/collections` — proxies to remote `/_emdash/api/collections`.
- `POST /emdash/{profile}/preview-diff` — server-side dry-run before any destructive write.

## Discovering the port

The port is kernel-assigned (`bind 127.0.0.1:0`).

- **Android** — the JNI bridge owns the listener and exposes the port to
  Kotlin callers through `NativeServer.port()` (added in a follow-up
  commit). UI tooling reads it from the bridge directly.
- **Desktop** — the server writes the port to
  `$XDG_RUNTIME_DIR/gemini-runtime/port` (or `~/.local/state/gemini-
  runtime/port` if `XDG_RUNTIME_DIR` is unset) on startup, and deletes
  the file on clean shutdown. CLI clients glob this for the address.

## Auth

Loopback-only. No bearer tokens, no signing, no allowlist — the OS
process boundary is the security boundary. If you need to expose the
server outside loopback, put it behind a reverse proxy you control;
this server explicitly will not bind to anything else.

## Stability

- The `native/openapi.yaml` spec is semver-versioned (`info.version`).
- Breaking changes to existing fields require a major bump and a
  parallel deprecation window of at least one minor.
- The `openai-compat` surface tracks the OpenAI spec as a moving
  target; we pin to a snapshot and document divergences here.

## Adding a new endpoint

1. Edit `native/openapi.yaml`. Group it under the right `tag`.
2. Add a route in `native/local-server/src/lib.rs` matching the new
   path. Route handlers receive a `&Request` and return
   `(status, content_type, body)`.
3. If the endpoint backs a typed Rust call, add the seam to the
   relevant crate (`model-runtime`, `agent-core`, `emdash-client`).
4. If you want the Android JNI bridge to mirror it, add the `extern fn`
   in `native/jni-shim/` and the Kotlin facade in the matching bridge
   module.
5. Update `docs/API.md` (this file).
