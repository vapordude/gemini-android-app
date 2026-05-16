# Pre-merge review pattern

A bounded, fan-out + triage flow for the moment when one or more PRs
are ready to merge but you want more confidence than CI's lint pass.
Distilled from the v0.3.0 cut, which ran exactly this pattern on
PRs #25 + #26 and caught five real bugs that CI would have shipped.

## When to use this

- ≥ 2 PRs in flight, especially when one is stacked on another.
- Any PR that touches `.github/workflows/*` or `app/build.gradle.kts`
  release plumbing (signing, versioning, native build, tag triggers).
- Any PR that adds a new top-level state surface (a new `StateFlow`
  in `ChatViewModel`, a new accordion in `SettingsSheet`, a new
  composable that reads multiple flows).
- Before pushing a release tag.

Skip this for one-line fixes, doc-only changes, or branches that are
already locally-reviewed end-to-end.

## The flow

```
1.  Three parallel review agents      ──┐
2.  One unfinished-code sweep         ──┤  ~3 min wall, in parallel
                                        │
3.  Triage findings                   ──┘
    │
    ├── Must-fix-pre-merge  ──> apply ──> push ──> wait for CI
    └── Polish              ──> park for next branch
    │
4.  CI green is the verification harness
    │
5.  Merge sequence
```

No recursion. The triage rule ("must-fix only") gives the loop a
terminator. Polish items go to a fresh `v0.x.y-polish` branch after
the release ships, not into the merging PR.

## The three reviewer roles

Spawn three `Explore` subagents **in parallel** (single message,
three tool calls — never sequential or recursion will cost you wall
time). Each gets a tight, non-overlapping prompt so their findings
converge instead of duplicating.

### A. Static correctness

Reads the Kotlin / Compose diff. Looks for: unstable `remember`,
missing keys on `mutableStateOf`, hoisting at wrong scope, leaking
coroutines, function-signature changes without caller updates, null
fallbacks, Compose recomposition pitfalls.

### B. Build + workflow sanity

Reads `.github/workflows/*.yml`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`, and any module `build.gradle.kts`
touched by the PR. Looks for: workflow execution order, cache key
correctness, NDK / cargo / SDK env inheritance, signing-step
sequencing, version-string consistency across the repo, manifest
placeholders.

### C. Unfinished code introduced by this PR

Distinct from a "leftover-spec sweep" — that finds *future work*.
This pass finds *introduced-this-PR* incomplete code: half-applied
refactors, dead branches, edge cases the author skipped, mismatched
assumptions (e.g., a new parameter at one call site missing at
another), `forEach` paths that don't handle empty input,
CHANGELOG entries missing features the diff actually shipped.

## Triage rule

Each finding gets one of three tags:

- **`[MUST-FIX]`** — bug, security issue, ships-broken risk, user-
  visible inconsistency, release-notes gap. Apply now, push, wait
  for CI.
- **`[POLISH]`** — cosmetic, stylistic, bikeshed, "this would be
  nicer if". Park for a follow-up branch.
- **`[DEFER]`** — needs a spec / multi-day work / architectural
  decision. Write a `docs/futures/<topic>.md` if not already there.

The merge does not block on `[POLISH]` or `[DEFER]`. Be ruthless.

## Convergence signal

When ≥ 2 reviewers independently flag the same issue, it's almost
certainly a real bug — promote it to `[MUST-FIX]` even if one
reviewer tagged it `[POLISH]`. The v0.3.0 cut saw the
`argsExpanded`-not-keyed bug surfaced by Reviewer A as critical and
Reviewer C as polish. It was critical.

## Verification harness

We don't have a local Android SDK / NDK in most of these sessions,
so the verification harness is **CI**, not local builds. Push the
fix commit, wait for green, then merge. Don't merge on stale CI
even if the diff looks obviously correct — workflow changes in
particular can pass locally and fail in CI for env reasons.

## What to do if findings cascade

If the first triage pass surfaces 10+ `[MUST-FIX]` items, the PR
is not ready to merge — it needs another author pass first.
Don't recurse the fan-out (each round costs wall time and context).
Instead: report the punch list back, close the PR as a draft, and
restart only after the author addresses the structural issues.

## Worked example: v0.3.0 (May 2026)

Three reviewers on PR #26 (which stacked PR #25's diff). Found:
- Reviewer A: `argsExpanded` state un-keyed (must-fix), LOCAL_AGENT
  null-path top-bar UX trap (must-fix), cross-mode state leak
  (polish).
- Reviewer B: workflow wiring correct ✓, OAuth redirect scheme
  flagged (out-of-scope — registered at Google's console), OpenAPI
  version drift (intentional per CHANGELOG L4).
- Reviewer C: `startNewChat` incomplete (must-fix),
  `initialAccordion` string mismatch (must-fix),
  `ON_DEVICE_MODELS.first()` defensive risk (must-fix), CHANGELOG
  missing 3 polish items (must-fix — release-notes gap).

Convergence: `argsExpanded` and the null-path fallback were flagged
by A and C independently. Six `[MUST-FIX]` items applied in one
commit (`ef61b4e`), pushed, CI green, merged.
