# Privacy

> **Kaimahi never extracts PII. Not from operators, not from collaborators,
> not from anyone. Edge inference does not need extracted data to work.
> Anyone forking this should hold the line.**

This document is the canonical privacy statement. It is intentionally
short and prescriptive. Code-level enforcement lives in the modules
named below; this file states what those modules promise.

## The four invariants

1. **No analytics. No remote logging. No crash reporters. No A/B
   telemetry. No usage metrics shipped off-device.** The runtime has
   exactly one outbound network surface — your configured cloud LLM
   endpoint (e.g. `generativelanguage.googleapis.com`) and your
   configured emdash instance(s). Nothing else.

2. **Telemetry stays on the device.** The `native/telemetry` crate
   writes typed-enum events to `filesDir/traces/*.jsonl`. The user can
   read and delete the file at any time. The Event enum is structurally
   incapable of carrying prompts, completions, tool args, or file
   contents — only enum tags, scalars, and short-tag error names. The
   `audit_no_pii` helper enforces this in CI.

3. **Training capture is opt-in and stays on-device.** The
   `native/agent-core/training.rs` module is OFF by default
   (`NoopCapture`). When the user explicitly enables it, captured
   tuples write to `filesDir/training/<session>.jsonl` and never leave
   the device unless the user explicitly exports them through the
   Storage Access Framework picker. Captured tuples DO contain user
   prompts and model completions — that's the point — but the user is
   the data controller. The runtime is the data processor.

4. **Memory is the operator's, not ours.** Persistent agent memory
   under `filesDir/memory/` is read by the agent driving *that* device
   and nothing else. No memory contents are ever attached to telemetry,
   sent to a cloud endpoint other than the one the operator chose, or
   shared between Kaimahi installs.

## What we don't do

- We don't fingerprint the device.
- We don't generate or store anonymous identifiers.
- We don't measure session length, retention, DAU, or any other
  product metric.
- We don't ship debug builds with extra collection enabled.
- We don't read user contacts, call logs, or any data outside the
  workspace the user explicitly opens via SAF.
- We don't inspect weight content for policy decisions. Architecture
  is detected from GGUF metadata; the bytes are opaque.

## What outbound traffic is allowed

Three categories, all under the operator's direct control:

| Destination | Trigger | Stoppable by |
| --- | --- | --- |
| Cloud LLM (Gemini API, etc.) | Operator enables a cloud backend and submits a turn through it. | Switching to `Local` in the inference toggle, or deleting the API key. |
| emdash-rs instance | Operator configures a profile and runs a tool that calls it. | Removing the profile in Settings. |
| Termux IPC (intra-device) | Operator triggers `run_shell_command`. | Disabling shell tools in Settings. |

There is no fourth category. If a future PR adds one, it must update
this table and the `PRIVACY.md` in the same commit, and provide an
opt-out toggle in Settings.

## What "edge inference" actually means

Edge inference here means: a quality model runs on the operator's
device and produces results that don't require sending the prompt or
the response to a third party. The local model is not a fallback;
it's a peer to whatever cloud engine the operator may also have
authenticated. The cloud option exists because the operator may want
it for a specific turn — never because the runtime needs it.

We do not collect data to "improve the experience for everyone". We
improve the experience by writing better code.

## How a forker keeps the promise

If you fork Kaimahi, you inherit this stance. Concretely:

1. Don't introduce a fourth outbound destination without updating this
   document and adding a user-visible toggle.
2. Don't widen the `telemetry::Event` enum to accept free-form payload
   strings. New fields must be typed enums or bounded scalars. The
   `Error.message` field accepts short tags only ("rate-limited",
   "timeout", "OOM"), not arbitrary user input.
3. Don't add a "phone home" build of any kind — not even for
   diagnostics. The `--features diag` build writes locally; if you
   want to add a remote-diag option, gate it behind an explicit
   user-supplied collector URL with no default.
4. Don't ship a build with training-capture defaulted on.
5. Don't store training data in a directory other than
   `filesDir/training/`, and don't add an auto-export path.

If any of these become impossible to honour for a legitimate reason,
fork the project under a different name. Kaimahi means *worker*. A
worker that surveils their employer is not a worker; they are
something else.

## Reporting

Issues that bear on this contract — surprising network traffic, fields
that look like they could carry PII, anything that doesn't match this
document — get triaged ahead of features. Open an issue with
`[privacy]` in the title.
