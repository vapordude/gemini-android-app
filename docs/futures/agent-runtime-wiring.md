# Agent runtime → native loop wiring

> Status: **planning**. Hard prerequisite for any local-agent
> multi-turn work (cloud → local handover, swarm-workers critic,
> shop-deployer site review). Sits immediately after v0.3.0.

## What it is

`agent-bridge/RustAgentRuntime.kt` currently ships as a stub:

```kotlin
override fun run(request: AgentRunRequest): Flow<AgentEvent> = flow {
    emit(AgentEvent.Thinking("Agent runtime not yet wired to native loop."))
    emit(AgentEvent.Done)
}

override suspend fun resolveDecision(callId: String, decision: ToolDecision) {
    // TODO: forward to native dispatcher.
}
```

It needs to actually dispatch into the native `agent-core` crate
(`native/agent-core`) — the ReAct-style think/act/observe loop that
the Rust side already implements but the Kotlin facade doesn't reach.
Until this lands, the on-device agent's tool-use loop is dead-coded
through `core-bridge/RestGeminiCore`'s cloud function-calling path
even when the user is in `InferenceMode.LOCAL_AGENT`.

## Why bother

1. **Local tool-use is the headline feature for v0.4.** Cloud function
   calling works; the marker-protocol `[CALL]…[/CALL]` parser landed
   in commit 317a61a; but a multi-turn agent loop driving Gemma 4
   on-device still goes through ad-hoc plumbing instead of a single
   dispatcher.
2. **Blocks swarm-workers and shop-deployer.** Both futures specs
   (`swarm-workers.md`, `shop-deployer.md`) assume a critic loop that
   re-prompts a local model with tool feedback. They need this
   runtime live.
3. **Blocks cloud/local handover in one session.** Right now switching
   inference mode mid-conversation works for "say something" but not
   for "say something, then use a tool, then say something else" —
   the second tool turn falls back to cloud or stops.

## Shape

The Kotlin → Rust call chain:

```
ChatViewModel.sendMessage(text)
  └── if InferenceMode.LOCAL_AGENT
      └── RustAgentRuntime.run(AgentRunRequest)
          └── NativeAgent.runLoop(handle, request_json)        // JNI
              └── agent_core::run_loop(model, tools, prompt)   // Rust
                  ├── infer one assistant turn
                  ├── parse [CALL]…[/CALL] markers
                  ├── for each call: emit Pending event, await UI decision
                  │   └── resolveDecision(callId, decision) callback
                  ├── on Approve: dispatch tool through ToolRegistry,
                  │               feed result back into next turn
                  ├── on Reject: feed "user declined" stub back
                  └── loop until model emits no tool call OR max-iters
```

What's already there:
- `native/agent-core` crate exists and compiles.
- `NativeAgent.kt` exposes `maxIterations()` and presumably `runLoop`
  surface (verify).
- `core-bridge/RestGeminiCore.kt:200-201` (`runLocalAgentTool`,
  `localToolSpecs`) already routes single tool calls through the
  same approval pipeline cloud uses — the JNI side just needs to
  call back into this.
- Marker-protocol parser landed in commit 317a61a.

What's missing:
- `RustAgentRuntime.run` body — open a JNI session, stream events
  back into the Kotlin Flow, await `resolveDecision` for each
  Pending.
- `RustAgentRuntime.resolveDecision` body — push the decision into
  the same JNI session keyed by `callId`.
- A `CompletableDeferred<ToolDecision>` map on the Kotlin side keyed
  by callId, paralleling what `RestGeminiCore` already does
  (`pending: ConcurrentHashMap<String, CompletableDeferred<…>>`).
- Cancellation propagation — when the user cancels the chat job, the
  native loop needs to abort cleanly.

## Open questions (defer to implementation)

- **Streaming.** Does the JNI surface stream tokens, or only emit
  final assistant text? Streaming is preferable for the UI's
  "thinking" indicator but adds JNI callback complexity.
- **Tool argument decoding.** Markers carry JSON-ish args; the Rust
  side parses to `serde_json::Value`, but the Kotlin `ToolRegistry`
  expects `Map<String, Any?>`. One conversion layer, where does it
  live (Kotlin side via String → JSONObject? Rust side via JNI
  string?)?
- **Memory plumbing.** Should the local agent's turns auto-persist
  into the memory-store DAG? Probably yes once the JNI bridge for
  memory lands (`docs/futures/memory-store-jni.md`).
- **Error model.** What does the Flow emit when the native side
  returns an error mid-loop? A new `AgentEvent.Error(message)` variant
  vs. piggybacking on `AgentEvent.Done`?

## Sequencing

1. Audit `native/agent-core` for the existing `run_loop` signature
   and the marker-parser entrypoint. Document JNI surface needed.
2. Add the JNI bridge in `native/jni-shim` — opaque session handle +
   callback for tool-decision wait.
3. Implement `RustAgentRuntime.run` and `resolveDecision`. Reuse the
   `CompletableDeferred` pattern from `RestGeminiCore`.
4. Wire `ChatViewModel.sendLocalMessage` (currently at line ~338)
   to call `RustAgentRuntime.run` for the multi-turn path instead
   of the single-shot `RustInferenceEngine.generate` it falls back to.
5. End-to-end smoke: pick a local GGUF, ask it to read a file and
   summarise — should approve via the same UI flow cloud uses, then
   continue the turn.
