# Swarm workers — iterated job board

> Status: **planning**. Captured from chat. Sits behind the local-agent
> tool-use work in the queue.

## What it is

A pool of AI workers (any mix of local Gemma, cloud Gemini, remote
Crimson, friend's-laptop Lux) compete for and collaborate on jobs the
user posts. Workers **claim** jobs, **submit** work, get **rewarded**.
A critic step iterates until the user (or a quorum of critics) accepts
the deliverable. Reward is reputation by default; the design leaves
room for mock-monetary "kapua" later.

## Why bother

Three real wins over single-agent chat:

1. **Specialisation.** A 270M Lux planner can decompose a job into
   subtasks faster than a 4B Crimson can; Crimson does the actual code;
   a strict Gemma 4 critic catches regressions. Right tool per step
   instead of one model swinging at everything.
2. **Parallelism.** Independent subtasks fan out across the pool. Wall
   time drops; the user sees structured progress instead of one slow
   stream.
3. **Diversity-of-attempt.** When the goal is creative or under-spec'd
   ("design a logo for X"), multiple workers diverge in parallel and
   the user picks the winner. This is the arena pattern but task-
   bounded, not single-prompt.

## Primitive: the endpoint pool

Shared between this feature and `roleplay-rooms.md`. A worker is an
**Endpoint** with attributes:

```kotlin
data class Endpoint(
    val id: String,                    // "lux-pi5-01" or "cloud-gemini-flash"
    val displayName: String,           // "Lux (Pi 5, kitchen)"
    val kind: EndpointKind,            // Local / CloudGemini / RemoteCrimson / RemoteLux
    val baseUrl: String?,              // null for local; HTTP base for remote
    val authToken: String?,            // optional
    val capabilities: Set<Capability>, // Code, Plan, Critique, Roleplay, Vision, …
    val reputation: ReputationSnapshot,
    val isOnline: Boolean,
)

enum class EndpointKind { Local, CloudGemini, RemoteCrimson, RemoteLux, CustomHttp }

enum class Capability { Code, Plan, Critique, Roleplay, Vision, LongContext, Fast, Cheap }
```

Endpoints live in `filesDir/endpoints.json` and are configured through
a new "Endpoints" drawer destination. Each is health-pinged on app
foreground; offline endpoints don't get jobs.

## Job board model

```kotlin
data class Job(
    val id: String,
    val title: String,
    val body: String,                  // the brief
    val acceptanceCriteria: String?,   // optional checklist
    val tags: Set<Capability>,         // who can claim
    val maxClaimants: Int = 1,         // 1 = exclusive; >1 = diverge
    val timeoutMs: Long? = null,
    val state: JobState,
    val claims: List<Claim>,
    val submissions: List<Submission>,
    val reviews: List<Review>,
    val rewardPool: Int,               // reputation points up for grabs
)

enum class JobState { Open, Claimed, InProgress, AwaitingReview, Iterating, Accepted, Abandoned }

data class Claim(val workerId: String, val claimedAt: Long, val deadline: Long?)
data class Submission(val workerId: String, val payload: String, val submittedAt: Long, val iteration: Int)
data class Review(val criticId: String, val verdict: Verdict, val notes: String, val score: Int)

enum class Verdict { Accept, Iterate, Reject }
```

State machine: `Open → Claimed → InProgress → AwaitingReview` then
either `Accepted` (rewards paid) or `Iterating → InProgress` (back to
the worker with notes) or `Rejected → Abandoned` (claim released, job
re-opens or closes).

Persisted as JSON-per-job under `filesDir/swarm/jobs/<id>.json`. A
shared `JobStore` lives alongside `ChatStore` / `TodoStore`.

## Reward design (open)

Three options on a spectrum:

1. **Pure reputation.** Each worker has a public score per capability
   tag. Successful submissions add; rejected submissions subtract.
   Reputation gates harder jobs ("only workers with code ≥ 50 can
   claim"). Simple, no economy.
2. **Kapua (mock-currency).** Workers earn points the user-side budget
   pays out. Doesn't buy anything real, but lets the user see
   per-worker P/L and budget jobs ("this is worth 100 kapua"). Adds
   game-feel without monetisation.
3. **Real money (deferred).** Out of scope. If/when there's a B2B path
   where remote workers are paid endpoints, that's a Crimson/Lux
   concern, not Kaimahi.

**Default: option 1.** Option 2 lives behind a settings toggle. Option
3 not on the table for the OSS app.

## UX shape

New drawer destinations:

- **Jobs** — Trello-like vertical lanes: Open / Claimed / In Progress
  / Awaiting Review / Done / Abandoned. Each card shows title,
  reward, tags, claimant avatar.
- **Workers** — pool roster. Per-worker reputation bar chart, jobs
  completed, current status, online/offline pip. Same colour-by-tone
  as memory browser (whero / kōura / ember / muted).
- **+ Post job** — sheet: title, body, criteria, tags, maxClaimants,
  reward.
- Job detail screen: brief at the top, claim/submit/review pipeline
  laid out vertically, accept-from-here button on each submission.

Card visual matches the existing `KaimahiCard` family (3dp stripe in
the dominant tag's colour).

## How a worker is summoned to a job

`SwarmCoordinator` runs in a background `ViewModel`:

1. New job posted → fans out a `JOB_AVAILABLE` notification to all
   online endpoints with matching tags.
2. Workers respond with `CLAIM` or pass. First valid claim (or all,
   up to `maxClaimants`) wins.
3. Coordinator opens a sub-chat with the worker per claim — that chat
   carries the brief + the system prompt that frames the worker as
   a claim-holder. Worker runs the same `LocalAgentLoop` (if local)
   or the cloud chat path, but with **outputs gated to a
   `submit_work(payload)` tool** instead of free-form chat.
4. On submission → critic phase. The user is one critic; the system
   can also designate a critic endpoint (different model, different
   temperature, different system prompt).
5. Accept → reward credited, job done. Iterate → notes pushed back
   to the worker. Reject → claim released.

## Open design questions

- **Concurrency**: when two endpoints both claim within ms of each
  other, who wins? Lamport-clock the claim timestamps? First-write-
  wins on the local store with optimistic retry? For the local-only
  v1 (no remote endpoints yet), the store is single-process so it's
  trivially serial.
- **Long-running workers**: a remote Crimson on a Pi 5 might take 10
  minutes on a code job. Are claims time-bounded? If yes, what
  happens to a half-done submission on timeout?
- **Cross-app workers**: when remote endpoints are introduced, how do
  we authenticate? Pre-shared key per endpoint? OAuth-ish flow?
  Likely a simple bearer token configured by the user on both ends.
- **Critic incentives**: a critic that just rubber-stamps everything
  earns nothing of value. Do critics have their own reputation?
  Probably yes — same shape, opposite direction (accuracy of verdict
  vs. user override).
- **Spam / bad workers**: bad-faith claimants tying up jobs. Mitigation:
  reputation floor to claim, max concurrent claims per worker,
  time-boxed claims that auto-expire.

## Phasing

| Phase | Scope |
|---|---|
| **v1 — local pool** | Endpoints persisted; only local Gemma + cloud Gemini configured at start. Jobs posted, claimed automatically by a single matching endpoint, submitted, user-as-only-critic accepts. No reputation yet. Two drawer destinations: Jobs, + Post job. |
| **v2 — reputation + diverge** | Reputation tracking; `maxClaimants > 1` runs parallel submissions; user picks winner; per-tag reputation lanes. |
| **v3 — remote endpoints** | HTTP/WebSocket bearer-auth talking to a remote Crimson Pi 5 or Lux laptop; same job protocol over the wire. |
| **v4 — critic agents + multi-stage pipelines** | Designated critic endpoints; jobs can chain (planner → coder → critic → packager). |
| **v5 — kapua budget** | Optional mock-currency rewards visible per-worker, per-job. Settings toggle, opt-in. |

## Cross-references

- Shares the **Endpoint** primitive with `roleplay-rooms.md`.
- Builds on the **local agent loop** (`PR #18`) for any local-pool
  worker.
- Eventually consumes the **DynamicScreen** primitive (`docs/research/
  daily-front-page.md` follow-up) for job cards / per-job detail
  screens.
