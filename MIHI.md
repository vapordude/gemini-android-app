# Mihi

> *Kaimahi* — Te Reo Māori for "worker", "employee". Software that works
> for you, not the other way around.

Kaimahi is built on the shoulders of giants. This document acknowledges
each upstream project the codebase folds together, and the wider
communities whose work makes this possible. No credit is implicit.

## Upstream forks

Three open-source projects merged into the shape you see here:

### gemini-android-app (Anthony Cidre / aciderix)
The original native Gemini coding client for Android. Provided the
Kotlin + Jetpack Compose chat surface, the function-calling tool
registry, the Termux shell bridge, the encrypted prefs, and the chat
persistence layer. The `nz.kaimahi.bridge` module is descended directly
from `core-bridge` in that project and remains the canonical path for
the cloud-Gemini API. Apache 2.0.

### DeepAgent (RUC-NLPIR / DeepAgent-scar fork)
Source of the marker-driven agent state machine
(`[BEGIN_TOOL_SEARCH]`, `[BEGIN_TOOL_CALL]`, `[FOLD_THOUGHT]`), the tool
registry shape, and the pattern of persistent memory via fold-and-recall.
We dropped the vLLM/OpenAI-compatible cloud client and re-parameterised
the loop over a local `InferenceBackend` trait, so the same agent code
drives any engine. MIT.

### EmDash CMS / emdash-rs
Content + deployment management. Provided the PortableText AST, the
collection / schema model, the `LlmProvider` and `PluginRunner` traits,
and the Wasm-sandboxed plugin host design. Kaimahi ships the *client*
side on Android (typed HTTP/JSON over `/_emdash/api/*`) and leaves the
full server stack to the upstream binary.

## Foundations

- **[Gemini API](https://ai.google.dev/)** — Google's cloud LLM service.
  Still the default cloud path inside Kaimahi; the local runtime sits
  beside it, not above it.
- **[Termux](https://termux.dev/)** — the open-source terminal emulator
  that makes "run a shell command from a chat bubble" possible. Pure
  community work; one of the most useful pieces of software on Android.
- **[Jetpack Compose](https://developer.android.com/jetpack/compose)**
  and **Material 3** — the UI toolkit.
- **[Kotlin](https://kotlinlang.org/)** — the language we move fast in.
- **[Rust](https://www.rust-lang.org/)** and the systems-programming
  community whose tools we deliberately keep at arm's length. The
  inference path is dependency-free by choice; the runtime owes the
  *idea* of safe systems code to that community even when the code
  doesn't link in.
- **GGUF / llama.cpp file format community** — the de-facto on-device
  weight format. Our loader implements the spec; no llama.cpp code
  itself is linked.
- **The wider open-source LLM ecosystem** — Mistral, Llama, Gemma,
  Stable Diffusion, Flux, and every fine-tune that flows through the
  same architecture-agnostic runtime. Content-neutral by construction.

## Spirit

Kaimahi is a tool. It does the work, it doesn't watch you while it does
it, and it tells you exactly what it's doing. The runtime is
content-neutral. The telemetry is local-only. The API surface is
documented in a single OpenAPI file you can read in one sitting.

If you fork this, please carry the mihi forward.
