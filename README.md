# Kaimahi

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Rust](https://img.shields.io/badge/Rust-stable-DEA584.svg?logo=rust&logoColor=white)](https://rust-lang.org/)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-2024.06-4285F4.svg?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Android API 26+](https://img.shields.io/badge/API-26%2B-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com/)

> 🇳🇿 Te Reo Māori + English · [🇫🇷 Version française](fr/README.md)

> **Kupu whakamāori / Translator's note** — He whakamāoritanga tuatahi
> tēnei. Ehara au i te kaikōrero matatau o te reo Māori; mā te hunga
> matatau e whakatika ngā hapa. He pai te whakapā mai mā te
> [Pull Request](https://github.com/vapordude/gemini-android-app/pulls).
> _This is a first-pass translation. I am not a fluent te reo Māori
> speaker; corrections from fluent speakers are welcome via PR._

---

**Kaimahi** he pūnaha tuhi-waehere ā-pūkoro mō Android. E rua ngā tauira
reo nui e mahi tahi ana — kotahi i runga i te taputapu (ā-taputapu),
tētahi atu i te kapua (ā-kapua). Ka pānui, ka tuhi, ka whakatika
pūkete tēnei kaimahi; ka rere i ngā tono pūoho (shell); ka waihanga i
ngā āhua; ka pupuri i te maharatanga mai i tētahi wā ki tētahi atu.
Kotahi te aronga: ko Kotlin + Jetpack Compose i te taha-kaiwhakamahi,
ko Rust hou-hangaia kei raro. Kāore he wharangi inference o waho.

**Kaimahi** (*Te Reo Māori for "worker"*) is a pocket coding workstation
for Android. Local on-device LLM + cloud LLM running side by side under
one agent loop. The agent reads and writes files, runs shell commands,
generates images, manages remote site deployments (via emdash-rs), and
persists memory across sessions. Kotlin + Jetpack Compose UI on top of a
from-scratch Rust runtime — no third-party inference libraries.

- Local agent + cloud agent **at the same time**. Auth both, fan out, or
  pick per-turn.
- **Topological + time-aware persistent memory** under
  `filesDir/memory/`. Notes form a per-session DAG with typed edges
  (`Follows`, `CausedBy`, `Contradicts`, `Supersedes`, `Refines`,
  `References`), optional expiry windows, and recency-decayed recall.
- Local OpenAPI 3.1 surface ([`native/openapi.yaml`](native/openapi.yaml))
  with OpenAI-compat endpoints — point any existing tool at
  `OPENAI_BASE_URL=http://127.0.0.1:<port>/v1`.
- Content-neutral runtime. No allowlist, no fingerprinting, no refusal
  layer. Architecture is read from GGUF metadata.
- API failures (cloud or tool) flow back to the agent as structured
  error events so it can adapt mid-turn.

## 🎯 What you can actually do with it · *Ngā mahi tūturu*

Ka taea e te tauira te whakarerekē i tō kaupapa — ehara i te
whakaahua noa. Ka huaki i ngā pūkete o tō wāhi mahi (mā te SAF, mā te
kōpaki ā-rohe rānei), ka whakatika tika, ka whakaatu i te
rerekētanga. Ka tukua kotahi tau, ka huri rānei i te aunoa-whakaae kia
mahi haere ai te tauira.

The model can modify your project, not just describe one. It opens
files in your workspace (SAF or local folder), edits them literally,
and shows you a diff. You approve once — or flip auto-approve and let
it iterate on its own.

- **Ask the model to modify a project, not just describe one.** It opens
  files in your workspace (SAF or local folder), edits them literally,
  and shows you a diff. You approve once — or flip auto-approve and let
  it iterate on its own.
- **Run shell commands from the conversation.** The Termux bridge drops
  the model into your workspace directory: `python foo.py`, `npm test`,
  `cargo build`, `pip install …`, `curl`, `git status`. Backgrounded
  processes (servers, watchers) keep running when the model's turn ends.
- **Sign in two ways, plus a no-auth escape hatch.**
  - **Reuse `gemini-cli` credentials.** If you've already run
    `gemini login` in Termux, tap "Load from Termux ~/.gemini" and the
    OAuth tokens come straight out of `~/.gemini/oauth_creds.json`.
    Requests route through the Code Assist API
    (`cloudcode-pa.googleapis.com`), the same endpoint gemini-cli uses;
    the free-tier 2.5 Pro quota on personal Google accounts comes with
    it.
  - **Continue with Google.** Play Services account picker, in-app OAuth.
  - **Skip — local model only.** No Google account at all. Use an
    imported GGUF file and stay offline.
- **Run a local Gemma 4 GGUF on-device.** Import an E2B or E4B Q4_K_M
  GGUF (Gemma 4 is the first Apache-licensed Gemma family) under
  Settings → Local model. Weights stay packed — the runtime dispatches
  `matvec_q4_k_row_major` directly on the Q4_K bytes, so peak RAM is
  the file size (~1.5 GB for E2B, ~3 GB for E4B), not 8× that. Q4_K /
  Q4_0 / Q8_0 / F16 / F32 / BF16 are all parsed; small tensors keep
  F32 storage, projection weights stay quantized. See "Known gaps"
  below for what this does **not** yet do.
- **Local-first cold start.** If a local model is already selected, the
  app skips the login screen and comes up in local-agent mode.
- **Generate images inline.** Both **Imagen** (dedicated picker in
  Settings → Model) and **Gemini 2.5 Flash Image** ("Nano Banana",
  auto-enabled when you pick it in the top-bar dropdown) save their
  outputs to the chat bubble as thumbnails. Cloud-backed only.
- **Send images for the model to analyse.** Tap the image icon, pick any
  photo from the gallery — it's sent as `inlineData` base64 in the next
  turn. Cloud-backed only.
- **Survive long sessions.** The app reports live token usage and
  auto-compresses the conversation into a fresh summary once the context
  window fills up.
- **Autosave every turn.** Close the app, come back later, the
  conversation is exactly where you left it. Name and save snapshots
  from the drawer for archive.
- **Export anywhere.** Drawer → Export as Markdown opens the Android
  share sheet — send the full conversation (text, code blocks, tables,
  image references) to any app.

## ✨ Feature breakdown · *Ngā āhuatanga matua*

E rua ngā ara reo-whakaheke mō te kōrero ā-kapua: te API Gemini
tūmatanui, me te API Code Assist (mā te `gemini-cli`). Ka mahia te
inference ā-taputapu mā `libkaimahi_native.so`, kua hangaia mai i te
takiwā Rust o roto (`native/`).

Two streaming transports cover cloud chat: the public Gemini API and
the Code Assist API (via `gemini-cli` credentials). On-device inference
runs through `libkaimahi_native.so`, built from the Rust workspace
(`native/`).

- **Two streaming transports for cloud chat:**
  - **Public Gemini API** (`generativelanguage.googleapis.com`) when
    signed in with Google Sign-In.
  - **Code Assist API** (`cloudcode-pa.googleapis.com/v1internal`) when
    using gemini-cli credentials. Access token + refresh token + Cloud
    AI Companion project id are stored encrypted; the app refreshes on
    expiry and re-onboards if `loadCodeAssist` reports the user
    isn't onboarded yet.
- **Local inference** through `libkaimahi_native.so` (built from
  `native/`): GGUF parser; packed Q4_K_M matvec kernel; the Gemma 4
  forward pass (per-layer SWA-vs-full attention dispatch, selective
  KV-share, per-head Q/K/weight-less-V RMSNorm, `gelu_pytorch_tanh`
  FFN, PLE injection, final logit softcap); RoPE with proportional
  rotation on full-attention layers; sampler (greedy / temperature +
  top-k). Tokens stream over a JNI callback into the same chat bubble
  as the cloud path.
- **Function calling** with 9 built-in tools:
  `read_file`, `write_file`, `edit_file`, `delete_file`,
  `list_directory`, `glob_files`, `grep`, `run_shell_command`
  (foreground or background), `generate_image` (Imagen). The model
  decides when to call them. **Cloud path only** — the local agent
  doesn't drive tools yet.
- **Safety on destructive tools**: every `write_file` / `edit_file` /
  `delete_file` / shell command shows an approval dialog with arguments
  and a diff (for edits) before it runs. One-tap "Auto-approve" toggle
  for trusted sessions.
- **Rich markdown rendering**: headings, numbered / bullet / task lists,
  inline + fenced code with a copy button, **bold**, *italic*, GFM
  tables, blockquotes, horizontal rules. Bare `https://…` URLs and
  `[label](url)` links are clickable and open in your browser.
- **Top-bar quick pickers**: tap the model name to switch models
  without opening Settings. Tap the workspace folder to "Open folder"
  (system Files app) or "Change folder" (SAF picker).
- **Multimodal input**: attach one or more images per turn; thumbnails
  render in the user bubble and persist across reloads. 15 MB cap per
  image. Cloud path only.
- **Dynamic model list**: fetched live from `/v1beta/models`, no
  hardcoded catalog. Custom model IDs accepted in Settings.
- **Diff viewer** in the tool-result bubble for `edit_file`.
- **Two languages**: EN / FR interface, full parity.

## 🛠 Prerequisites · *Ngā mea me whai i mua*

Hei whakamahi i te ara kapua, me whai i tētahi pūkenga Google — mā
Play Services, mā `gemini-cli` rānei. Hei whakamahi i te ara
ā-taputapu anake, kāore he take pūkenga Google; mēnā ka hiahia koe ki
te tukutuku pūoho, me whakauru a Termux.

Cloud chat needs one Google credential path — either Play Services or
`gemini-cli`. Local-only mode needs no Google account at all. Termux is
required if you want shell tool calls.

- **Android 8.0+** (API 26+).
- For cloud chat — **one of**:
  - A Google account you can sign into with Play Services.
  - Or [`gemini-cli`](https://github.com/google-gemini/gemini-cli) installed
    in Termux and `gemini login` run once, so
    `~/.gemini/oauth_creds.json` exists.
- **Recommended** — [Termux](https://f-droid.org/packages/com.termux/)
  from F-Droid or [the GitHub releases](https://github.com/termux/termux-app/releases)
  (⚠️ **not** the Play Store version, abandoned since 2020). Required
  for the gemini-cli login path; required for `run_shell_command`.
- **Optional (offline inference)** — one or more GGUF model files you
  can import from Android storage in Settings. See "Known gaps" below
  for which sizes / quants actually fit on a phone today.

To build from source:
- **Android SDK** (API 34+), **JDK 17** on `JAVA_HOME`.
- **Rust toolchain** with `aarch64-linux-android` + `x86_64-linux-android`
  targets, plus `cargo-ndk` and the Android NDK r27c, for native binary
  rebuilds. The CI workflow at `.github/workflows/native.yml` shows the
  exact incantation.

## 🚀 Installation · *Te whakatū*

E rua ngā ara whakatū: tikina mai te APK kua oti te hanga, hangaia
rānei mai i te puna.

Two install paths: download the prebuilt APK, or build from source.

### Option 1: pre-built APK · *Te APK kua oti te hanga*

Download the latest APK from the
[Releases page](https://github.com/vapordude/gemini-android-app/releases).
Both debug-signed and release-signed APKs are published on each tag.

### Option 2: build from source · *Hangaia mai i te puna*

```bash
git clone https://github.com/vapordude/gemini-android-app
cd gemini-android-app
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 💻 First-run setup · *Te whakaritenga tuatahi*

Kōwhiria tētahi o ngā ara takiuru i te mata tuatahi. Mēnā kua oti i a
koe te `gemini-cli`, kōwhiria "Load from Termux ~/.gemini"; mēnā kāore
he pūkenga Google, kōwhiria te ara ā-taputapu anake.

Pick a login path on first launch. If you've already run `gemini-cli`,
pick "Load from Termux ~/.gemini"; if you have no Google credentials at
all, pick local-only mode.

1. **Login screen — pick a path**:
   - *Load from Termux ~/.gemini* — recommended if you've already used
     `gemini-cli`. Tap "Start gemini login in Termux" first if you
     haven't.
   - *Continue with Google* — Play Services account picker, then
     in-app OAuth.
   - *Use local-only mode* — skip auth entirely. You'll need to import
     a GGUF before you can talk to anything.

   Tokens (and the resolved Cloud AI Companion project id, if any) are
   stored encrypted on device.
2. **Top-bar folder name → Change folder**: pick a workspace under
   `/storage/emulated/0/` (avoid `/Android/data/…`, unreachable to
   Termux). The file tools operate relative to this folder.
3. **Settings → Termux shell** (one-time): follow the 3-step guide to
   allow `run_shell_command`. You need Termux installed and
   `termux-setup-storage` run once.
4. **Top-bar model name**: tap to pick a model. Use `gemini-2.5-flash`
   for everyday coding, `gemini-2.5-pro` for harder reasoning,
   `gemini-2.5-flash-image-preview` for inline image generation
   (requires billing on the API-key path; the Code Assist path inherits
   gemini-cli's quota and may differ).
5. **Settings → Local model (GGUF)** (optional): import a local model
   file from any document provider, select it for use, and delete stale
   files. Once selected, the next cold start skips the login screen and
   comes up in local-agent mode.

## 🧱 Architecture · *Te hanga*

He kaupapa Gradle maha-momo. Ka noho tonu te ara Gemini ā-kapua o
mua; kua tāpiri he momo hou — te inference ā-taputapu, te koromeke
māngai, te whakahaere emdash, me te tūmau OpenAPI ā-rohe.

Multi-module Gradle project. The existing cloud-Gemini path stays
untouched; new modules add on-device inference, an agent loop, emdash
management, and a local OpenAPI server.

| Module | Role |
| --- | --- |
| `:app` | Compose UI, ViewModels, Activity |
| `:core-bridge` | REST Gemini client, tool registry, Termux IPC, SAF, prefs |
| `:domain` | Pure types (`GeminiMessage`, `ToolSpec`, `GeminiEvent`, `AgentRuntime`, `InferenceEngine`, `TraceEvent`, `DeploymentConfig`…) — no Android |
| `:ui-components` | Shared design tokens + `AppScreen` scaffold |
| `:inference-bridge` | Kotlin facade over the native LM/image runtime |
| `:agent-bridge` | Kotlin facade over the native agent state machine |
| `:emdash-bridge` | Typed HTTP client + Compose screens for remote emdash-rs instances |
| `native/` (Rust) | from-scratch tensor math, GGUF loader, agent loop, telemetry, hand-rolled HTTP/1.1 server. See `native/README.md`. |

**Cloud chat** flows through `RestGeminiCore`, which has two transports:

- **Public Gemini API**, used when `RestGeminiCore.init()` receives an
  `api_key` or a Play-Services OAuth access token.
- **Code Assist API** via `CodeAssistSession` (in `core-bridge/codeassist`),
  used when `init()` receives a `refresh_token` from the gemini-cli
  `oauth_creds.json` parse, or when prefs report a previously-onboarded
  Code Assist session. The session owns refresh + onboarding so the
  REST layer just sees a working bearer token.

**Local chat** goes through `inference-bridge::RustInferenceEngine`,
which holds a JNI handle from `nativeOpenSession(path)`,
`nativeGenerate(handle, prompt, callback, …)`, and friends. The native
side dequantizes weights from GGUF and runs the Gemma forward pass.

No DI framework, no Room. `SharedPreferences` + JSON files under
`filesDir`.

## ⚠️ Known gaps · *Ngā āputa kua mōhio kē*

E pono ana mātou mō ngā wāhi kāore anō kia oti. Ko te āputa nui rawa
ko te whakawā parity i te ara Gemma 4 — kua oti te porotiti, engari
kāore anō kia whakatauritea ki te tauira HuggingFace o waho.

We're honest about where the build is still wobbly. The biggest gap is
end-to-end parity verification on the Gemma 4 forward pass — ported,
unit-tested, but not yet compared against a HuggingFace reference.

- **Gemma 4 forward-pass parity is unverified end-to-end.** The math
  is a line-by-line port of `llama.cpp/src/models/gemma4.cpp` (every
  `ggml_mul_mat` mapped to a row-major `matvec` with inline shape
  comments) and the per-piece kernels are unit-tested, but no PyTorch
  / reference parity harness has compared a full prompt → next-token
  distribution against a baseline yet. First-token on-device is the
  current ground truth.
- **Only Gemma 4 is supported on-device.** Gemma 2 / 3 GGUFs still
  parse and tokenize, but their forward pass was retired with the
  port. Their architectures differ enough (no PLE, no
  per-layer-heterogeneous head_dim, no selective KV-share, different
  RoPE schedule) that wedging them through the Gemma 4 path produces
  garbage. If you need Gemma 3 back, the previous SwiGLU decoder lives
  in git history at `v0.2.0`.
- **SIMD intrinsics aren't wired.** Scalar Rust matvec only. On a
  Cortex-A76 expect ~0.05–0.3 tok/s for E2B at Q4_K_M. The NEON path
  is the highest-leverage follow-up.
- **`use_double_wide_mlp=true`** (E2B) is treated as the standard
  split-tensor FFN layout — confirmed against the GGUF converter
  scripts. If a future fine-tune ships fused gate+up under a
  non-standard tensor name the loader will throw `MissingMetadata`
  with the exact name it asked for.
- **MoE path (`enable_moe_block=true`)** raises a clear error at load
  time — that layout ships in the 26B-A4B variant, not E2B/E4B.
- **Local tool grammar is marker-based, not native function calling.**
  The on-device Gemma model drives the same tool registry the cloud
  path uses (read_file / write_file / edit_file / list_directory /
  glob_files / grep / run_shell_command). It emits
  `[CALL]name(json)[/CALL]` in its output stream; the host parses,
  runs (with the same approval dialog the cloud path uses for
  destructive tools), and feeds back `[RESULT id=X ok=true]…[/RESULT]`
  in the next turn. Works on smaller models without a fine-tuned
  function-calling head, at the cost of being more brittle than a
  native protocol — if the model writes the markers wrong, the parser
  treats it as prose. The fallback is the standard cloud path.
- **Local multimodal.** Vision encoder isn't wired on-device. Image
  attachments only work on the cloud path.
- **Code Assist response shape.** The Code Assist client matches the
  payload shapes documented in the public `gemini-cli` source. If
  Google changes them server-side, the chat throws "Code Assist HTTP
  …" and the cure is a request-shape patch.
- **Native HTTP server.** `native/local-server` and the `local API` /
  `OPENAI_BASE_URL` story documented in `docs/API.md` are spec-level —
  the JNI bridge for the Android side is not wired into the running
  app yet, and the OpenAI-compat endpoints return their declared 501s
  for diffusion / video.
- **Unwired Compose surfaces.** A `RoomViewModel` exists for
  multi-persona chat rooms but no screen renders it. The
  `native-driver` module (separate `libgemma4.so` skeleton under
  `rust/crates/gemma4-*`) is a parity-oracle workspace and isn't
  included in `settings.gradle.kts`; it ships unused.

If you want to help close any of these, the relevant entry points are
called out in `docs/PORTING.md` and `docs/SCAFFOLDING.md`.

### On-device runtime principles · *Ngā mātāpono ā-taputapu*

- **No external libs where possible.** The tensor core is hand-rolled.
- **Content-neutral.** Weights are opaque; architecture comes from
  GGUF metadata. No allowlist, no fingerprinting, no refusal layer.
- **Non-extractive.** Local traces only, typed-enum fields. No
  analytics, no remote logging.
- **Architecture-agnostic.** Language models + diffusion models live
  side by side under `native/model-runtime/src/arch/`. New
  architectures slot in without core changes. NPU/GPU acceleration
  plugs in via the `Delegate` trait. See [`docs/PORTING.md`](docs/PORTING.md).

## ⚙️ Advanced configuration · *Whirihora matatau*

### Auto-compression · *Whakapiri aunoa*

Ka whakaaturia e te tauira te tatauranga kupu-hua i tēnā, i tēnā
whakautu. Inā nui ake i te paepae (70% te paerewa), ka whakarāpopotohia
te kōrero ki te wāhanga hou i te paparua.

The model reports `usageMetadata.totalTokenCount` on every response.
Once `total / inputTokenLimit` crosses the threshold (default **70 %**),
the conversation is summarised into a fresh session in the background —
a non-blocking banner signals the operation.

Tune the threshold in **Settings → Auto-compression** (50 % → 95 %) or
disable it entirely.

### Image generation · *Te waihanga āhua*

- **Imagen** (`imagen-3.0-generate-002` by default, picker in
  Settings → Model) — called via the `generate_image` function tool
  when the model decides one is needed.
- **Gemini 2.5 Flash Image / Nano Banana** — pick it as the chat
  model, the app automatically enables `responseModalities: [TEXT, IMAGE]`
  on every request so the model returns images inline.
- Images are saved to `<filesDir>/attachments/` and render as
  thumbnails in the bubble; chat exports and reloads preserve them.

Both image paths require billing on the Google Cloud project linked to
your API key — the Gemini free tier has a quota of **0** for these
models.

### Background shell commands · *Ngā tono pūoho ā-muri*

`run_shell_command` accepts a `background: true` flag so the model can
start long-running processes (web servers, file watchers, training
loops) without Termux's 12-second IPC timeout killing them. Output
lands in `$HOME/.gemini-bg/run-<id>.log`, tailable on demand.

## 🧪 Gemini compatibility · *Te hototahitanga Gemini*

Tested with:

- `gemini-2.5-pro` / `gemini-2.5-flash` / `gemini-2.5-flash-image-preview`
- `gemini-2.0-flash`
- `gemini-1.5-pro` / `gemini-1.5-flash`

**Gemma** models (`gemma-2-*`, `gemma-3-*`, `gemma-4-*`) appear in the
picker but don't support function calling — they'll work for pure chat
but the tool stack won't be available. On-device Gemma 4 (E2B / E4B
GGUFs) is the only locally-runnable family right now; see "Known gaps".

## 🔌 Local API (OpenAPI) · *Te API ā-rohe*

Ka whakaputa te rorohiko Rust i te kirimana OpenAPI 3.1 i runga i
`127.0.0.1`. Kotahi te kirimana ([`native/openapi.yaml`](native/openapi.yaml)),
e rua ngā kaiwhakatutuki — te JNI mō Android, me te tūmau HTTP/1.1
motuhake.

The Rust runtime exposes a single OpenAPI 3.1 contract
([`native/openapi.yaml`](native/openapi.yaml)) served on `127.0.0.1`:

- **`native`** — canonical NDJSON surface (`/info`, `/models`,
  `/generate`, `/agent/run`).
- **`openai-compat`** — drop-in for existing tooling: `/v1/models`,
  `/v1/chat/completions` (incl. streaming + tools + multimodal),
  `/v1/completions`, `/v1/embeddings`, `/v1/images/generations`,
  `/v1/images/edits`, `/v1/videos/generations` (501 until diffusion /
  video arches ship).
- **`health`** — `/healthz`, `/readyz`, `/metrics` (Prometheus text).
- **`diagnostics`** — `/diag/probes`, `/diag/snapshot` (only present
  in `--features diag` builds).
- **`telemetry`** — `/traces` (always on, non-extractive).
- **`emdash`** — local proxy to remote emdash-rs instances.

Full surface guide: [`docs/API.md`](docs/API.md).

## 🤝 Contributing · *Te takoha mai*

He pai te whakapā mai. Whakawehe i tētahi peka, tuhia ō panonitanga,
pana mai. Tirohia te [`CONTRIBUTING.md`](CONTRIBUTING.md) mō ngā tikanga
hāpaitia me whai.

Contributions welcome. Fork a branch, commit your changes, push.
[`CONTRIBUTING.md`](CONTRIBUTING.md) has the five load-bearing
contracts.

1. **Fork** the project.
2. Create a branch: `git checkout -b feature/my-feature`.
3. Commit with a clear message: `git commit -m "Add my feature"`.
4. Push: `git push origin feature/my-feature`.
5. Open a Pull Request.

### Dev setup · *Te whakaritenga mō te whakawhanake*

- **Language**: Kotlin 1.9.24, target JDK 17.
- **UI**: Compose BOM 2024.06.00 + Material 3.
- **Versions centralised** in `gradle/libs.versions.toml`.

Useful commands:
```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:lint                 # Android lint
./gradlew :core-bridge:test         # unit tests
```

## 📄 License · *Te raihana*

Distributed under the **Apache 2.0** license. See [`LICENSE`](LICENSE)
for details.

## 📚 Documentation · *Ngā tuhinga*

| File | What's in it |
| --- | --- |
| [`PRIVACY.md`](PRIVACY.md) | Four invariants, outbound-traffic table, rules for forkers. |
| [`SECURITY.md`](SECURITY.md) | Reporting path, scope, coordinated-disclosure window. |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Five load-bearing contracts contributors must respect. |
| [`MIHI.md`](MIHI.md) | Acknowledgement of upstream forks + foundations. |
| [`CHANGELOG.md`](CHANGELOG.md) | What's shipped. |
| [`docs/README.md`](docs/README.md) | Doc index — read-in-order + read-by-task. |
| [`docs/AGENTIC.md`](docs/AGENTIC.md) | Agent loop, memory (topological + time-aware), training capture. |
| [`docs/API.md`](docs/API.md) | Unified OpenAPI 3.1 surface — native, OpenAI-compat, health, diag, telemetry, emdash. |
| [`docs/BRAND.md`](docs/BRAND.md) | Voice, palette (pounamu / kowhai / kauri), brand mark, badges. |
| [`docs/PORTING.md`](docs/PORTING.md) | New model architecture, vendor NPU/GPU delegate, three upstream projects. |
| [`docs/SCAFFOLDING.md`](docs/SCAFFOLDING.md) | Six common extension scaffolds for forkers. |
| [`docs/SCREENS.md`](docs/SCREENS.md) | Canonical `AppScreen` shape. |
| [`docs/STYLES.md`](docs/STYLES.md) | Per-token technical reference. |
| [`native/README.md`](native/README.md) | Rust workspace crate map + OpenAPI tags. |
| [`native/openapi.yaml`](native/openapi.yaml) | Source-of-truth API contract. |

## 🛡️ Privacy · *Te tūmataiti*

**Kāore a Kaimahi e tango i ngā PII. Ehara mai i ngā kaiwhakahaere,
ehara mai i ngā kaimahi tahi, ehara mai i tētahi atu.** Ka taea te
inference ā-tapa me te kore PII. Tirohia [`PRIVACY.md`](PRIVACY.md) mō
ngā mātāpono e whā e mau ana te waehere nei, ngā kāwai puta e toru e
whakaaetia ana, me ngā ture mā te kaiwhakapō kia mau tonu ai te ingoa.

**Kaimahi never extracts PII. Not from operators, not from collaborators,
not from anyone.** Edge inference works without it. See
**[`PRIVACY.md`](PRIVACY.md)** for the four invariants this codebase
holds, the three categories of outbound traffic that are allowed, and
the rules a forker has to keep to be allowed to keep the name.

## 🪶 Mihi (acknowledgements) · *He mihi*

E tū ana a Kaimahi i runga i ngā pakihiwi o ētahi atu. Tirohia
[`MIHI.md`](MIHI.md) mō te mihi katoa; he poto:

Kaimahi stands on shoulders. See [`MIHI.md`](MIHI.md) for the full
acknowledgement; in short:

- **Upstream forks**: the original `gemini-android-app` (aciderix), the
  Gemini CLI by Google, [DeepAgent](https://github.com/RUC-NLPIR/DeepAgent)
  for the agent state machine, and the EmDash CMS for the management
  surface.
- **Foundations**: [Gemini API](https://ai.google.dev/),
  [Termux](https://termux.dev/), [Jetpack Compose](https://developer.android.com/jetpack/compose),
  Material 3, the GGUF file format community, and the broader Rust
  systems-programming ecosystem we deliberately keep at arm's length.

Project link: <https://github.com/vapordude/gemini-android-app>
