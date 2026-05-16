# Agentic patterns

How Kaimahi runs an agent loop with a cloud LLM and an on-device LLM at
the same time, how failures flow back into the agent's prompt so it can
adapt, and how the dual stream becomes training data for the local
model — entirely on-device, owned by the operator.

## The shape

```
        ┌──────────────────────────────────────────┐
        │                Kaimahi UI                │
        └──────┬─────────────────────────────┬─────┘
               │  user goal + tool approvals  │
        ┌──────▼──────────────────────────────▼─────┐
        │             AgentRuntime (Rust)            │
        │  ┌──────────────────────────────────────┐  │
        │  │   DeepAgent marker state machine     │  │
        │  │   [BEGIN_TOOL_SEARCH] / _CALL /      │  │
        │  │   [FOLD_THOUGHT] / structured errors │  │
        │  └──┬───────────────────────────────┬───┘  │
        │     │ InferenceBackend trait        │      │
        │  ┌──▼──────────────┐    ┌───────────▼───┐  │
        │  │  CloudGemini    │    │  LocalGemma4  │  │
        │  │  (Android REST) │    │  (native LM)  │  │
        │  └──┬──────────────┘    └───────────┬───┘  │
        │     │                               │      │
        │  ┌──▼───────────────────────────────▼───┐  │
        │  │   MultiBackend(policy)               │  │
        │  └──┬───────────────────────────────────┘  │
        │     │  optional: capture (prompt,         │
        │     ▼   cloud_resp, local_resp, accepted) │
        │  ┌──────────────────────────────────────┐  │
        │  │   TrainingCapture (FileCapture)      │  │
        │  │   filesDir/training/<session>.jsonl  │  │
        │  └──────────────────────────────────────┘  │
        │                                             │
        │  ┌──────────────────────────────────────┐  │
        │  │   MemoryStore (FileMemoryStore)      │  │
        │  │   filesDir/memory/<session>.jsonl    │  │
        │  └──────────────────────────────────────┘  │
        └─────────────────────────────────────────────┘
```

## Dual authentication, both at once

Kaimahi treats cloud and local engines as peers, not alternatives. The
canonical setup:

1. **Cloud Gemini** — API key set up via `Settings → Account`,
   encrypted in `EncryptedSharedPreferences`. Always available unless
   rate-limited or offline.
2. **Local Gemma 4 (E2B or E4B)** — GGUF in `filesDir/models/`, loaded
   into the Rust runtime on app start. Always available when the file
   is present.

Both stay authenticated simultaneously. The agent picks per call via
`MultiBackend(Policy::*)`:

| Policy | Behaviour |
| --- | --- |
| `PreferFirst` | Try cloud first; fall through to local on error. Recommended default. |
| `PreferLast` | Same in reverse — bias on-device, fall up to cloud if it falters. |
| `RoundRobin` | Alternate. Useful when you want both engines exercised across a long session. |

The UI's `InferenceModeToggle` exposes three states — `Cloud`, `Local`,
`Auto` (multi-backend). The badge in the top bar reflects the engine
that *actually* ran the current turn.

## Failures flow back to the agent

API errors and tool errors are not silent. They become a structured
block injected into the agent's transcript before its next prompt cycle:

```
ERROR [inference/complete]: rate-limited
ERROR [tool/run_shell_command]: exit code 1: permission denied
ERROR [network/emdash]: connect timed out after 30s
```

`AgentError` (Rust) carries `ErrorKind ∈ {Inference, Tool, Network,
Validation}`, a `source` tag (which call site), and the message. The
agent literally *sees* its own failures and can:

- Switch backends (`MultiBackend` does this automatically on
  `Inference` errors).
- Retry a tool with different arguments.
- Surface a tighter approval request to the user.
- Fold the failed step into memory so it doesn't try the same thing
  next session.

The `max_iterations` cap (50 by default) still protects against
runaway retry loops; the structured error path just gives the agent a
real chance to recover before that cap fires.

## Persistent memory — topological + time-aware

`memory.rs` provides a `MemoryStore` trait. `[FOLD_THOUGHT]` markers
emitted by the agent trigger a `fold(Note { ... })` call. Two impls
ship: `InMemoryStore` (default, session-scoped) and `FileMemoryStore`
(JSON-lines under `filesDir/memory/<session>.jsonl`, hand-rolled
encoder/decoder, append-only, survives restarts).

### Note shape

```rust
pub struct Note {
    pub id: NoteId,                  // sortable: ms-since-epoch + tiebreak seq
    pub ts_ms: u128,
    pub session: String,
    pub kind: NoteKind,              // Fold | ToolResult | Error | Fact | Observation
    pub text: String,                // agent-authored short summary
    pub links: Vec<Link>,            // topology: typed edges to other notes
    pub valid_until_ms: Option<u128>,// time-awareness: optional expiry
    pub tags: Vec<String>,           // cheap retrieval index
}

pub enum LinkKind {
    Follows, CausedBy, Contradicts, Supersedes, Refines, References,
}
```

Notes form a **per-session DAG**. Each note can point to earlier notes
via typed edges. "I learned X because of Y" is a `CausedBy` link.
"Actually X was wrong, use Y instead" is a `Supersedes` link. The
agent maintains the graph by emitting links when it folds.

### Five orthogonal recall queries

| Method | Use |
| --- | --- |
| `recall(session, max)` | Chronological, newest first. Back-compat. |
| `recall_window(session, since_ms, max)` | Time-windowed ("last 24 h"). |
| `recall_by_tag(session, tag, max)` | Index lookup ("everything tagged `deploy`"). |
| `recall_topology(session, root, depth, kinds)` | DAG traversal — "what's downstream of this fact, following only `CausedBy` edges". |
| `recall_decayed(session, now_ms, half_life_ms, max)` | Recency-weighted: `score = exp(-elapsed × ln 2 / half_life)`. Older notes still appear, just ranked lower. |

`recall_decayed` is the workhorse for prompt construction — older
context fades smoothly instead of getting hard-cut at a context-window
boundary.

### Time-awareness

Two pieces:

- **Notes are wall-clock timestamped** at creation time (`ts_ms`), and
  the `NoteId` encodes that time lexicographically so notes sort by
  creation order even after process restarts.
- **Notes can expire.** `valid_until_ms` lets the agent record facts
  with explicit validity windows: "Bob is on-call until Friday at
  17:00", "the deploy is in flight", "the cert expires in 30 days".
  `recall_decayed` skips expired notes; `recall_window` lets you
  inspect them on demand.

The agent's prompt template includes the current wall-clock time, so
the model can reason about elapsed time when recalling
(e.g. "we discussed this 4 hours ago" / "last week").

### Privacy

Memory is the operator's, not ours. Memory contents are never attached
to telemetry, sent to a cloud endpoint other than the one the operator
chose, or shared between Kaimahi installs. See `PRIVACY.md`.

## Training-data capture

This is the powerful part: when both engines run for the same prompt,
the user gets a free distillation corpus. `TrainingCapture` is the
seam.

```rust
pub struct CapturedTuple {
    pub session: String,
    pub prompt: String,
    pub cloud_response: Option<String>,
    pub local_response: Option<String>,
    pub accepted: AcceptedSide,    // Cloud | Local | Neither | Both
    pub cloud_latency_ms: Option<u64>,
    pub local_latency_ms: Option<u64>,
}

pub trait TrainingCapture: Send + Sync {
    fn record(&self, t: CapturedTuple);
}
```

Three uses:

1. **Distillation.** Fine-tune the local model on cloud responses the
   user accepted. The cloud agent becomes the teacher; the local model
   chases its trajectory.
2. **Preference pairs.** Tuples where `accepted ∈ {Cloud, Local}` are
   labelled preference data — feed them to DPO/IPO/SimPO.
3. **Drift evaluation.** Hold out a slice; periodically run the local
   model on it and compare against the captured cloud response with a
   simple BLEU/ROUGE check or an external judge.

### What gets captured

By default, **nothing**. The default impl is `NoopCapture`. The user
opts in via `Settings → Training → Enable capture`, which swaps in
`FileCapture { dir: filesDir/training/ }`.

When enabled, every turn that runs through `MultiBackend` writes one
JSONL line:

```json
{"ts":1715800123456,"session":"site-redesign","prompt":"...","cloud":"...","local":"...","accepted":"cloud","cloud_ms":1240,"local_ms":86}
```

### What it costs

- Disk: ~5–50 KB per turn depending on completion length. A 1 GB cap
  rotates the oldest session files out.
- Latency: zero on the hot path — `FileCapture::record` is a
  fire-and-forget append protected by a mutex.
- Privacy: the file never leaves the device unless the user explicitly
  exports it (SAF picker, opt-in).

### Wiring it up

In your bridge layer:

```kotlin
val capture: TrainingCapture =
    if (settings.trainingCaptureEnabled)
        FileCapture(context.filesDir.resolve("training"))
    else NoopCapture

val agent = AgentRuntime(
    backends = MultiBackend(listOf(cloudGemini, localGemma4), Policy.PreferFirst),
    memory = FileMemoryStore(context.filesDir.resolve("memory")),
    capture = capture,
)
```

The agent's run loop emits a `capture.record(tuple)` after every
finalised turn where both engines produced output. The UI exposes the
captured tuples in `TraceViewerScreen` filtered to `kind = training`.

### Honest stance

This is a powerful feature. Distilling a frontier model into a local
one has both technical and licensing implications depending on which
cloud provider you're using; check their ToS before exporting a
distilled checkpoint. Kaimahi captures the data — what you do with it
is your call.

## The full agent contract

`AgentRuntime` is the only object you wire up. Its dependencies are:

| Slot | Trait | Canonical impl |
| --- | --- | --- |
| Inference | `InferenceBackend` | `MultiBackend` over cloud + local |
| Tools | `ToolDispatcher` | Reuses the existing 9 tools from `core-bridge` |
| Memory | `MemoryStore` | `FileMemoryStore` |
| Capture | `TrainingCapture` | `NoopCapture` or `FileCapture` |

Every slot is a trait. Substitute any one for testing, vendor support,
or research without touching the others.
