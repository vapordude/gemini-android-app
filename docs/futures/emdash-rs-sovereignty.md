# Emdash-rs — own the runtime surface end-to-end

> Status: **planning**. The flagship post-v0.3.0 architectural move.
> Pairs with the internal-rename spec and the agent-runtime-wiring
> spec; together they unhitch Kaimahi from the cloud-Gemini umbilical
> at the surface level (UI + identity) and at the runtime level
> (model + agent loop + tools).

## What it is

`native/emdash-core` + `native/emdash-client` + the
`emdash-bridge` Kotlin module already exist as a *contract* — see
`native/openapi.yaml`, which describes a "Kaimahi local runtime API"
with these properties (locked in by the spec, line 17–25):

- **Local only.** All traffic stays on loopback.
- **Non-extractive.** No telemetry, no analytics, no remote logs.
- **Architecture-agnostic.** GGUF metadata is the source of truth.
- **Stable semver.** Daily-driver friendly.
- **OpenAI-compat as convenience.** The canonical surface is the
  native `/info`, `/generate`, `/agent/run` — OpenAI-compat is a
  shim for existing tooling.

But the app today still routes the **primary chat path** through
cloud `RestGeminiCore` against `generativelanguage.googleapis.com` /
`cloudcode-pa.googleapis.com`. Emdash-rs is the *spec* and the *Rust
implementation skeleton*, not the *production surface*. This is the
gap.

Making emdash-rs "ours fully" means:

1. Implement the HTTP/1.1 local server (no third-party framework,
   per the OpenAPI principles) bound to a kernel-assigned loopback
   port. Done in Rust under `native/local-server`.
2. Wire the JNI surface (`Java_com_gemini_*` per the OpenAPI spec —
   though the prefix should follow the internal-rename outcome) so
   the Android app calls emdash-rs directly for `/info`,
   `/generate`, `/agent/run` instead of dispatching through cloud.
3. Make cloud the **explicit fallback**, not the implicit default.
   The chat flow defaults to emdash-rs if a local model is selected
   and the lib is present; falls back to cloud only when the user
   has explicitly opted in or no local model is loaded.
4. Ship the OpenAI-compat routes (`/v1/chat/completions`,
   `/v1/embeddings`) as side outputs of the canonical surface so a
   user's existing editor / CLI tooling targets emdash-rs unchanged
   (the original Qwen white-paper requirement).

## Why bother

1. **Identity coherence.** Right now the architecture diagram says
   "Kaimahi" at the UI and "cloud Gemini" at the runtime. After
   this lands, both layers say Kaimahi — emdash-rs is the runtime,
   cloud Gemini is one of several inference backends sitting behind
   it.
2. **Sovereignty narrative is true, not aspirational.** The
   POSITIONING.md framing only fully lands when the daily-driver
   path doesn't leave the device. Right now it can; the user has
   to opt into local mode. That should flip.
3. **Unblocks shop-deployer.** The shop-deployer spec assumes a
   sovereign HTTP service on the operator's machine — emdash-rs IS
   that service, extended with the AEMS routes. One stack, not two.
4. **Enables the OpenAI-compat use case.** Any editor / CLI plugin
   that already speaks OpenAI suddenly works against the user's
   own device. Massive ecosystem leverage from a single contract.
5. **Grant-readiness.** A pitch built around indigenous AI
   sovereignty needs more than a chat UI. It needs a runtime that
   demonstrably keeps work on-device. Emdash-rs is that
   demonstration; until it ships, the pitch leans on intent
   instead of proof.

## Shape

```
native/local-server/              (Rust)
├── server.rs                     Hand-rolled HTTP/1.1, loopback bind
├── routes/
│   ├── info.rs                   GET  /info          → runtime + model metadata
│   ├── generate.rs               POST /generate      → one-shot inference
│   ├── agent_run.rs              POST /agent/run     → multi-turn tool-use loop
│   ├── compat_chat.rs            POST /v1/chat/completions
│   ├── compat_embeddings.rs      POST /v1/embeddings
│   ├── compat_images.rs          POST /v1/images/generations  (when SD lands)
│   └── compat_models.rs          GET  /v1/models
├── handlers/
│   ├── model_pool.rs             Load / pool / share loaded GGUFs across requests
│   ├── tool_dispatch.rs          Route /agent/run tool calls to host
│   └── stream.rs                 SSE streaming for chat completions
└── port_file.rs                  Write port to XDG_RUNTIME_DIR / Android equivalent
                                  for daily-driver discovery

native/emdash-core/               (Rust — already exists, formalise contracts)
├── types/info.rs                 InfoResponse, ModelMeta
├── types/generate.rs             GenerateRequest/Response (matches openapi.yaml)
├── types/agent.rs                AgentRunRequest/Event/Response
└── types/compat.rs               OpenAI-compat request/response shapes

emdash-bridge/                    (Kotlin)
├── NativeEmdash.kt               JNI surface (already exists; flesh out)
├── EmdashClient.kt               High-level Kotlin API over JNI
├── EmdashHttpClient.kt           Fallback HTTP client (talks to local-server
                                  via loopback on desktop / CLI scenarios)
└── EmdashTransport.kt            Picks JNI vs HTTP based on platform

core-bridge/                      (Kotlin — refactor)
└── ChatCore.kt (renamed)         Dispatcher: emdash-rs primary, cloud fallback
```

## Migration in three steps

### Step 1: Implement the local server + JNI surface for /info + /generate
- Smallest end-to-end: app starts → calls `emdash.info()` → renders
  metadata in About screen.
- No agent loop, no tools. Just round-trip.
- Validates the JNI bridge and contract layout end-to-end.

### Step 2: /agent/run with the marker-protocol tool-use loop
- Depends on `docs/futures/agent-runtime-wiring.md`.
- Replace `RustAgentRuntime.run()` stub with a JNI call into
  `agent_run.rs`.
- Tool dispatch callback routes back to Kotlin `ToolRegistry`.

### Step 3: OpenAI-compat routes + port discovery
- Add `/v1/*` routes as shims over the canonical surface.
- Write port to `XDG_RUNTIME_DIR/kaimahi-runtime/port` on desktop;
  expose via JNI on Android.
- Document discovery for CLI / editor consumers.
- Editor plugins (Continue, Cursor-equivalents) can now target
  Kaimahi unchanged.

## Open questions

- **Loopback binding on Android.** Background services binding to
  loopback need careful battery / lifecycle management.
  Foreground service with a small notification? Or JNI-direct only
  on Android, HTTP only on desktop?
- **Model pool eviction.** Multiple concurrent requests against
  one loaded GGUF needs sharing; multiple GGUFs need eviction.
  LRU on load count? On idle time?
- **Streaming under JNI.** SSE is trivial over HTTP; over JNI
  requires a callback. Both paths need to exist.
- **Auth on the local server.** Loopback-only is the auth, but a
  curious app on the same device could reach it. Add a one-shot
  token in the port file?
- **Naming.** "emdash" is the internal name today. Worth keeping?
  Or fold into "Kaimahi runtime" once the internal-rename PR
  lands? The OpenAPI doc already says "Kaimahi local runtime API"
  — that's the canonical name.

## On the grant question

A grant pitch built around what's in the repo today would lean on:

- Bilingual te reo Māori + English documentation (already true)
- On-device LLM inference, sovereign data posture (true in spec,
  partially true in production — emdash-rs closes the gap)
- Te ao Māori-rooted framing (MIHI.md, KaimahiBrand, whakatauki
  baked in)
- Open-source MIT / Apache licence stance (already true)
- Working Android artefact (true after v0.3.0)

What strengthens the pitch substantially:

1. **Emdash-rs as the production runtime** (this spec). Demonstrable
   sovereignty, not aspirational.
2. **Shop-deployer working end-to-end** (`docs/futures/shop-deployer.md`).
   Tangible economic-sovereignty story tied to NZD settlement.
3. **A real partnership** — iwi, kura, NGO, accessibility
   advocate. Grants reward demonstrated reach.
4. **Measurable outcomes** — N daily-active local-inference users,
   X minutes of agent work done without leaving the device.

Plausible funding routes worth investigating (this is partial — a
proper search of the grants database would surface more):

- **Te Mātāwai** — te reo Māori revitalisation. Strong fit if the
  language-learning angle is concrete.
- **Callaghan Innovation** — R&D grants, deep-tech / AI focus.
  Repayable + non-repayable tracks.
- **MBIE Endeavour Fund** — larger, longer-horizon research grants.
  Heavier institutional asks.
- **NZ on Air / Te Tāhū o te Mātauranga** — digital and education
  innovation, especially for under-served audiences.
- **Vodafone NZ Foundation, ASB Foundation, etc.** — corporate
  community grants, smaller but faster.

My honest read: the repo is at the stage where a small grant
(Callaghan project capability, Te Mātāwai pilot) is fundable on
the current evidence. A mid-tier grant (Endeavour Smart Ideas,
MBIE deep-tech) wants emdash-rs landed + at least one
deployment-in-the-wild story. A big grant (Endeavour research
programme, Marsden equivalent) wants partnerships + measurable
impact, which is a 12-month arc, not a sprint.

Worth running a grants-database search once we know what scale of
ask and which programme suits your timeline.
