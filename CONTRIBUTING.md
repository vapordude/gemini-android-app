# Contributing to Kaimahi

Welcome. Kaimahi is built to be forked — every seam is a trait, every
architecture is plug-in, every doc is meant to make extension obvious.
Patches that respect the contracts below are welcome.

## Before you write code

Read in this order:

1. [`MIHI.md`](MIHI.md) — what we owe upstream.
2. [`PRIVACY.md`](PRIVACY.md) — the four invariants. Load-bearing.
3. [`docs/AGENTIC.md`](docs/AGENTIC.md) — agent loop, memory,
   training capture.
4. [`docs/SCAFFOLDING.md`](docs/SCAFFOLDING.md) — six common extension
   points.
5. The doc most relevant to your change (`API.md`, `PORTING.md`,
   `BRAND.md`, `SCREENS.md`, `STYLES.md`).

## The five contracts (don't break these without a conversation)

1. **Non-extractive.** No analytics, no remote logging, no anonymous
   IDs, no DAU/retention/session metrics. The
   `telemetry::Event` enum's `Error` variant uses
   `tag: &'static str` so the compiler refuses runtime payload.
   Don't widen this without a privacy review.
2. **Content-neutral runtime.** Architecture comes from GGUF
   metadata; weight content is opaque. No allowlist, no fingerprinting,
   no refusal layer inside `model-runtime`. Use-policy lives above
   the runtime (tools, approvals).
3. **No third-party inference libraries** in `tensor-core`. The math
   path is dependency-free on purpose. If you need linalg, vendor a
   small crate and feature-gate it.
4. **No fourth outbound destination** (today: cloud LLM, configured
   emdash, Termux IPC). Adding one requires updating `PRIVACY.md` in
   the same commit and a user-visible Settings toggle.
5. **One OpenAPI contract.** `native/openapi.yaml` is the source of
   truth for both the JNI bridge and the local HTTP server. Endpoints
   that exist in one and not the other are bugs.

## Build + test (Kaimahi quick check)

```bash
# Android
./gradlew :app:assembleDebug

# Rust
cargo test    --manifest-path native/Cargo.toml --workspace --lib
cargo clippy  --manifest-path native/Cargo.toml --workspace --all-targets -- -D warnings
cargo fmt     --manifest-path native/Cargo.toml --all -- --check
```

All four must pass before push. CI runs all four; gradle errors get
surfaced as GitHub annotations on the public PR page (see
`.github/workflows/android.yml`).

## Commit style

- One concern per commit. The history is the spec.
- Subject line: ≤ 72 chars. Imperative.
- Body: explain *why* this commit, not *what* (the diff is what).
  Reference docs that move alongside the change.
- Reference the contract you're touching if you're touching one:
  `[privacy]`, `[content-neutral]`, `[api]`, `[brand]`.

## What's welcome

- New model architectures under `native/model-runtime/src/arch/`.
- Vendor delegate crates (Hexagon QNN, Neural Engine, Vulkan, …).
- Better tensor kernels (real NEON / AVX impls, replacing scalars).
- New tools for the agent registry.
- Bug fixes with reproductions.
- Doc fixes — typos, broken cross-refs, stale numbers.
- Translations of user-facing strings.

## What's a conversation, not a PR

- Renaming public traits or modules.
- Adding deps to `tensor-core`.
- Anything that touches one of the five contracts above.
- New top-level docs.

Open an issue first; we'd rather agree on shape than reject a finished
PR.

## Code review

- Privacy-impacting PRs get tagged `[privacy]` and require a separate
  reviewer.
- API surface PRs require an `openapi.yaml` update in the same diff.
- Native-crate PRs run `cargo clippy -D warnings` and 100% of the
  workspace tests in CI.

## Licence

By contributing, you agree your contribution is licensed under
[Apache 2.0](LICENSE).

## Reporting issues

- Bugs: open a GitHub issue with a reproduction.
- Security / privacy: see [`SECURITY.md`](SECURITY.md).
- Discussion / questions: open a Discussion or a question issue.

Kia ora.
