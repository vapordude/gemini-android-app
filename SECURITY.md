# Security

## Reporting

Open an issue on GitHub with `[security]` in the title, or — if the
finding is sensitive enough that public disclosure would create a
window for exploitation — open a private GitHub Security Advisory
on the repo. Either way:

- Describe what you observed.
- Describe what you expected.
- Include the smallest reproduction you can.
- Tell us how you'd like to be credited (or whether you'd prefer not
  to be).

We aim to acknowledge within a few days. Privacy-impacting issues
(see below) skip the queue.

## Scope

Kaimahi is a local-first Android app + Rust runtime. The security
surface that matters most:

1. **Privacy invariants** (see [`PRIVACY.md`](PRIVACY.md)). Anything
   that looks like:
   - Unexpected network traffic from the app or the runtime.
   - PII fields appearing in `filesDir/traces/*.jsonl`.
   - Training capture writing when it should be off.
   - A build that ships training-capture defaulted on.

   …is the highest priority class of report. Triaged ahead of
   functionality issues.

2. **Tool sandboxing.** `run_shell_command` drops into Termux. Sandbox
   escapes (running outside the user's chosen workspace,
   privilege-escalation through tool args, etc.) are in scope.

3. **Encrypted secrets.** API keys and emdash auth tokens live in
   `EncryptedSharedPreferences`. Issues that bypass or weaken that
   storage are in scope.

4. **Native runtime safety.** The `tensor-core` crate is the only
   place we allow unsafe SIMD. Out-of-bounds reads, use-after-free,
   data races — please report.

5. **JNI surface.** Type confusion at the JNI boundary, unchecked
   sizes from native back to Kotlin, missing null checks — please
   report.

## Out of scope

- Anything that requires the operator to install a hostile APK side
  of the app. The Android process boundary is the trust boundary.
- The behaviour of the cloud Gemini API itself. Bugs in Google's
  service should be reported to Google.
- The behaviour of a remote emdash-rs instance the operator chose to
  configure. We provide a client; the server's policies are
  upstream.
- Distillation / training-capture policy of frontier model providers.
  Check the cloud provider's ToS before exporting captured data.

## The non-extractive contract

Re-affirming what [`PRIVACY.md`](PRIVACY.md) makes load-bearing in
the type system: **Kaimahi never extracts PII.** Telemetry is typed
enums + bounded scalars, by construction. Training capture is opt-in
and stays on-device. Memory is the operator's. Adding a "phone home"
build of any kind is a non-starter — fork under a different name if
you need that.

If you find a privacy-impacting issue, opening a `[security]` issue
is the right first step. We will not pressure you to keep findings
quiet beyond a reasonable coordinated-disclosure window.

## Coordinated disclosure

For findings that warrant a coordinated disclosure window:

- We aim for ≤ 30 days between acknowledgement and public advisory
  for high-severity findings.
- Lower-severity issues land in the next release; the advisory ships
  with the patch.
- Credit goes to the reporter in [`CHANGELOG.md`](CHANGELOG.md)
  unless they decline.
