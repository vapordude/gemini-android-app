# Internal rename — drop `Gemini` from interior types

> Status: **planning**. Sits after the v0.3.0 cut. Not user-visible;
> not a release blocker. But blocks a clean "Kaimahi is its own
> thing" identity claim at the code level. Sister doc to the
> shop-deployer and agent-runtime-wiring specs.

## What it is

The v0.3.0 cut finishes the **user-visible** Cathedral rebrand —
logo, chrome strings, copy, dialogs, markdown export. Interior
class names, package paths, and Rust crate identifiers still wear
the original Gemini prefix:

- `domain/.../GeminiCore.kt` — interface `GeminiCore`, sealed class
  `GeminiResult`, data class `GeminiMessage`
- `domain/.../ToolTypes.kt` — sealed class `GeminiEvent`
- `core-bridge/.../RestGeminiCore.kt` — main cloud client
- `core-bridge/.../CloudGeminiBackend.kt` — backend adapter
- `core-bridge/.../auth/GeminiCliAuthService.kt` — Code Assist OAuth
- `native-driver/src/main/kotlin/com/gemini/localdriver/` — package
  path mismatched with declared package `nz.kaimahi.*`
- `app/src/main/kotlin/com/gemini/app/ui/room/` — same mismatch
- `core-bridge/src/test/kotlin/com/gemini/bridge/` — test path
  mismatch

Plus two **brand-flip blockers** at the OAuth boundary that the
v0.3.0 cut deliberately did not touch:

- `app/build.gradle.kts` —
  `manifestPlaceholders["appAuthRedirectScheme"] = "com.google.gemini.android"`
- `core-bridge/.../GeminiCliAuthService.kt:177` —
  `REDIRECT_URI_APPAUTH = "com.google.gemini.android:/oauth2redirect"`

These are coupled — the scheme value must match the URI passed to
AppAuth — and changing them **requires coordinated re-registration
of the redirect URI at the Google OAuth console**. Can't be
unilaterally renamed from the codebase.

## Why bother

1. **Identity coherence.** When the app says "Kaimahi" everywhere
   the user looks and `RestGeminiCore` everywhere a developer looks,
   onboarding new contributors carries a constant minor friction
   ("which name is real?"). One refactor closes that.
2. **Grant / sovereignty narrative.** A funding pitch built around
   sovereign Aotearoa-rooted AI is harder to sell when the
   architecture diagram is still labelled with the upstream cloud
   provider's product name. Interior naming is the last yard.
3. **OAuth blocker is real.** Even if we leave interior class names
   alone for now, the `com.google.gemini.android` redirect URI is a
   *visible* identity claim — anyone who inspects the manifest sees
   it. Until that's changed at Google's console + here, the brand
   flip is incomplete.

## Shape

Two passes, sequenced:

### Pass 1: Pure internal rename (mechanical, code-only)

Rename map:

| Old | New | Rationale |
|---|---|---|
| `GeminiCore` (interface) | `ChatCore` | Describes the abstraction (chat sessions), not a vendor |
| `RestGeminiCore` | `RestCloudCore` | Describes the implementation (REST against cloud) |
| `CloudGeminiBackend` | `CloudBackend` | Drop redundant prefix |
| `GeminiMessage` | `ChatMessage` | Generic over backend |
| `GeminiEvent` | `ChatEvent` | Generic over backend |
| `GeminiResult` | `ChatResult` | Generic over backend |
| `GeminiCliAuthService` | `GoogleCodeAssistAuth` | Names the protocol it speaks, not the CLI it borrows tokens from |

Plus directory moves:

- `native-driver/src/main/kotlin/com/gemini/localdriver/` →
  `native-driver/src/main/kotlin/nz/kaimahi/localdriver/`
- `app/src/main/kotlin/com/gemini/app/ui/room/` →
  `app/src/main/kotlin/nz/kaimahi/app/ui/room/`
- `core-bridge/src/test/kotlin/com/gemini/bridge/` →
  `core-bridge/src/test/kotlin/nz/kaimahi/bridge/`

(The package declarations inside these files already say
`nz.kaimahi.*` — Kotlin tolerates the mismatch but it's a smell.)

Tooling: IntelliJ "Rename" refactor handles 95%; remaining 5% is
imports in untouched modules + comments + the README. Plan: do it
in one PR, force review on the file-tree summary, not the rename
diff.

### Pass 2: OAuth redirect URI (coordinated with Google console)

1. Register a new redirect URI at the Google Cloud OAuth client:
   either `nz.kaimahi.app:/oauth2redirect` or `kaimahi://oauth`.
2. Update `appAuthRedirectScheme` and `REDIRECT_URI_APPAUTH` in
   lock-step.
3. Update the AndroidManifest.xml comment.
4. Ship.
5. Old auth tokens stored from the previous scheme keep working
   (they were granted server-side, not bound to the scheme post-
   issuance), but new OAuth dances use the new scheme.

## Open questions

- **`gemini-cli` references in code paths and docs.** The external
  CLI is named `gemini-cli` — those references stay literal. But
  the path resolution code at `~/.gemini/oauth_creds.json` is
  fine; it's reading Google's CLI's storage location.
- **Public API stability.** If any of these classes appear in a
  consumer-facing API (currently nothing does — the app is single-
  artefact), renaming is fine. If a future MCP server or plugin
  surface depends on `GeminiCore`, we need a deprecation alias.
- **`gemini-android-app` repo name.** GitHub repo URL still has
  the old name. Renaming is a one-click GitHub setting + a
  redirect. Lowest priority.

## Sequencing

1. Ship v0.3.0 with interior names untouched. CHANGELOG documents
   the OAuth-redirect URI as a known follow-up.
2. Spike Pass 1 (mechanical rename) in a single PR after v0.3.0
   lands. CI catches anything the refactor misses.
3. Pass 2 (OAuth) needs a maintainer with Google Cloud console
   access; coordinate timing so we don't break existing auth.
