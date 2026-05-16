# Memory-store JNI bridge

> Status: **planning**. README line 112 documents the gap: "the memory
> browser shows project recency until the Rust memory-store JNI bridge
> lands." Pairs naturally with the agent-runtime wiring spec.

## What it is

The codebase has two independent memory surfaces that don't talk to
each other:

- **Rust side** (`native/agent-core` or similar): a topological +
  time-aware persistent memory store under `filesDir/memory/`. Per-
  session DAG, typed edges, expiry windows. Documented in
  `docs/AGENTIC.md` and committed to in the README's "topological +
  time-aware persistent memory" bullet (line 37-38).
- **Kotlin side** (`app/src/main/kotlin/nz/kaimahi/app/ui/memory/
  MemoryBrowserScreen.kt`): a UI that surfaces "project recency" —
  i.e. recently-edited workspace files, not memory-DAG nodes. It's a
  placeholder until the JNI bridge exists.

This spec covers the JNI bridge that lets the Kotlin side query
(and the Rust agent loop write to) the same memory DAG.

## Why bother

1. **Memory across sessions is the project's posture.** "Persists
   memory across sessions" is in the README intro. Right now that's
   half-true: the Rust side persists internally, but no UI surfaces
   it and no app-level workflow consumes it.
2. **Unblocks meaningful local-agent behaviour.** Once the agent
   runtime is wired (`docs/futures/agent-runtime-wiring.md`), turns
   need somewhere to land so the next turn can reference them.
3. **Foundation for the daily-front-page and daily-todo screens.**
   Both already exist as composables; they're currently hand-fed
   stubs. Real memory-DAG queries would turn them into the "what
   matters today" surfaces they were designed for.
4. **Foundation for shop-deployer attribution.** Each deployed AEMS
   struct should land as a memory node tied to its product, payment
   route, and TTL — so the agent can answer "which shop sold what
   last week" without re-deriving from logs.

## Shape

JNI surface (proposed, refine during impl):

```rust
// native/jni-shim or memory-store/jni.rs
pub fn memory_recent(limit: usize, since_epoch_ms: i64) -> Vec<MemoryNode>;
pub fn memory_query(filter: MemoryFilter) -> Vec<MemoryNode>;
pub fn memory_insert(node: MemoryNodeInput) -> NodeId;
pub fn memory_link(from: NodeId, to: NodeId, kind: EdgeKind);
pub fn memory_expire(before_epoch_ms: i64) -> usize;  // returns pruned count
```

`MemoryNode` carries: id, kind (`Turn | File | Decision | Shop | Tool
| ScreenScaffold | …`), payload (JSON blob), created-at, expires-at,
session-id, parent-ids (DAG predecessors).

Kotlin facade in `core-bridge/memory/` (new package):

```kotlin
interface MemoryStore {
    suspend fun recent(limit: Int = 50, sinceMs: Long = 0): List<MemoryNode>
    suspend fun query(filter: MemoryFilter): List<MemoryNode>
    suspend fun insert(node: MemoryNodeInput): NodeId
    suspend fun link(from: NodeId, to: NodeId, kind: EdgeKind)
    suspend fun pruneExpired(): Int
}

class NativeMemoryStore : MemoryStore { /* JNI calls */ }
```

Consumers:
- `MemoryBrowserScreen` swaps its current "project recency" data
  source for `MemoryStore.recent()`.
- `ChatViewModel` calls `memory.insert(Turn(text=…))` after each turn
  lands successfully (cloud or local).
- `RustAgentRuntime` (once wired) reads `memory.query(session=…)`
  as context for the next turn's prompt assembly.
- `DailyTodoScreen` + `DailyFrontPageScreen` swap stubs for real
  queries.

## Open questions

- **Cross-session DAG vs session-scoped?** README implies cross-
  session ("persists memory across sessions") but per-session DAG.
  Probably: session-scoped within DAG, but query API spans sessions.
- **Privacy default.** All on-device, never exported by default. The
  Export-as-Markdown path should NOT include memory nodes unless the
  user opts in — flag explicit, not assumed.
- **Encryption at rest.** `filesDir/memory/` is already in app-private
  storage. Do we add a secondary symmetric key from `SecurePrefs` on
  top? Possibly overkill for v0.4.
- **Schema evolution.** Memory nodes are long-lived. Need a migration
  story for `kind` and payload schema before we ship. Probably a
  `schema_version` field on each node, lazy upgrade on read.
- **Search.** Plain time-window query is enough for v1; full-text
  / embedding search is a later spec.

## Sequencing

1. Audit the existing Rust memory-store crate, document its current
   API and where the DAG actually persists (`filesDir/memory/<what>`).
2. Define the JNI surface — 5 functions above plus error types.
3. Implement `NativeMemoryStore` Kotlin facade with the JNI bridge in
   `inference-bridge` (lives next to `NativeInference`).
4. Repoint `MemoryBrowserScreen` from project-recency to
   `MemoryStore.recent()`.
5. Wire `ChatViewModel` post-turn insert + auto-prune-expired on
   chat start.
6. Once agent-runtime wiring lands, wire query-on-prompt-assembly
   inside `RustAgentRuntime`.
