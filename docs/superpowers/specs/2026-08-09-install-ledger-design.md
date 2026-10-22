# Skein "install ledger" — design

**Date:** 2026-08-09
**Status:** approved (design), pending implementation plan

## Problem

`SyncComponents.forSync` runs client sync with `DiffOptions.additiveOnly()`
(`DeletionPolicy.KEEP` for every extra local file). That fix (commit `363ee1e`) was
correct for its goal — never delete a mod the player installed themselves — but it is
blanket: **nothing extra is ever removed, for any reason**, including files Skein itself
put there in a previous sync.

Two concrete symptoms:

1. A pack mod updates and its jar filename changes (e.g. version-in-filename packs).
   The old jar is never cleaned up; both versions load together.
2. A server admin removes a mod from the pack entirely. The client that already has it
   keeps it forever — sync never removes it.

Both share one root cause: the diff engine has no way to distinguish "a file the player
added" from "a file Skein installed that the manifest no longer wants." Both must be
solved together, since they are the same missing capability.

## Goals

- A local file gets quarantined (never hard-deleted — existing project principle) only
  if it was previously installed *by Skein*, and is no longer in the current manifest.
- A file the player added themselves is never touched, on any sync, ever — the guarantee
  `363ee1e` established must not regress.
- Works identically on a fresh install (nothing is removable yet — correct, since Skein
  hasn't installed anything to track) and improves from the second sync onward.
- Loader-agnostic: lives entirely in `skein-core` / `skein-bootstrap` / `skein-app`, so
  both Forge and NeoForge get it automatically with no loader-specific code.
- Verifiable in this environment (`javac` + standalone driver / JUnit) — no ForgeGradle
  toolchain required, matching how all shared-module work here has been verified.

## Non-goals

- Reconciling a mod's *content* changing without its path changing (already handled: a
  hash mismatch on an existing path is a normal `UPDATE` fetch).
- Multi-profile / multiple mod folders sharing one ledger. `PlatformPaths` is already
  strictly per-instance (one `gameDir()`/`skeinDir()` per client instance, same as
  `RootHashCache`) — one ledger per instance is the existing architectural assumption,
  not a new constraint.
- Any change to the loader adapters (`skein-forge`, `skein-neoforge`).

## Approaches considered

1. **Install ledger (chosen).** Persist the set of paths the manifest declared as of the
   last successful sync. On the next sync, an extra local file is only quarantine-eligible
   if it's a member of that persisted set. Mirrors the existing `RootHashCache` pattern
   (read-before, write-after, best-effort, lives under `skeinDir()`).
2. **Per-file marker file** (e.g. `somemod.jar.skein` next to each installed mod).
   Rejected: clutters the mods folder the player sees, more files to manage, no benefit
   over a single ledger for this use case.
3. **Blanket quarantine of all extras** (the pre-`363ee1e` behavior). Rejected: this is
   exactly the destructive behavior that commit already fixed.

## Architecture / data flow

```
                     ┌─────────────────────────────────────────┐
                     │           SkeinClientSession.run()        │
                     └─────────────────────────────────────────┘
  1. read ledger ──► InstalledPathsCache.read()  (skeinDir()/installed-paths.txt)
                     Set<RelativePath> previouslyManaged (empty on fresh install)
                            │
  2. build orchestrator ──► SyncComponents.forSync(..., previouslyManaged)
                            uses DiffOptions.safe(previouslyManaged)
                            instead of DiffOptions.additiveOnly()
                            │
  3. subscribe to EventBus for SyncEvent.DiffComputed.desiredPaths() and
     .fetchedPaths() (both captured, not yet written — sync hasn't finished)
                            │
  4. orchestrator.run() ──► SyncOutcome
                            │
  5. if outcome.isSuccess():
       newLedger = (previouslyManaged ∪ capturedFetchedPaths) ∩ capturedDesiredPaths
       InstalledPathsCache.write(newLedger)
     else: ledger untouched (sync didn't complete, state is unchanged)
```

### Why the ledger is `(previouslyManaged ∪ fetchedPaths) ∩ desiredPaths`, not plain `desiredPaths`

An earlier version of this design wrote `desiredPaths` — `manifest.desiredByPath().keySet()`,
captured at diff time — directly as the new ledger. **That was a bug, found in review and
fixed before this feature shipped.** `desiredPaths` includes every path the manifest
wants, including paths that were already correct on disk and so were classified
`unchanged` by the diff engine — Skein never fetched or committed those. Concretely: a
player already has `mods/jei-1.0.jar`, byte-identical to what the pack later declares;
the first sync's diff finds the hash already matches and never touches the file, yet
plain `desiredPaths` would still record it as "installed by Skein." If the pack later
drops `jei`, a ledger built that way would wrongly quarantine a file Skein never
actually put there — a direct violation of this feature's own absolute guarantee
(`363ee1e`: a player-added file is never touched, under any circumstance).

The corrected formula only trusts what a given sync run actually *fetched*
(`SyncEvent.DiffComputed.fetchedPaths()`, derived from `SyncPlan.fetches()` — i.e.
`ChangeType.ADD`/`UPDATE`, never `unchanged`), carried forward across syncs via
`previouslyManaged`, and trimmed to what's still wanted via `∩ desiredPaths`:

- A file that was always `unchanged` and never fetched is never added to the ledger by
  the `∪ fetchedPaths` term, and was never in `previouslyManaged` either (nothing put it
  there) — so it never enters the ledger at all, ever, no matter how many syncs run. It
  stays permanently untracked, exactly like a player-added file. This is the fix.
- A file Skein genuinely fetches in some sync enters via `fetchedPaths` that round, and
  persists across later syncs where it's merely `unchanged` — because it carries forward
  via `previouslyManaged` each round, even though it's not being re-fetched.
- When the pack later drops a path from the manifest, it falls out of the new ledger via
  the `∩ desiredPaths` term — but the *deletion decision* for that sync already correctly
  used the *previous* ledger (read before diffing, which still contained it), so it still
  gets quarantined this round, and then correctly stops being tracked afterward. Deletion
  planning is unaffected by this fix — only what gets *written back* changes.

Writing the ledger back is only correct if `SyncOutcome.isSuccess()` guarantees every
planned fetch that entered `fetchedPaths` was actually committed. Verified by reading
the implementation (not assumed):

- `DefaultDownloadService.execute()` — loops every `PlannedFetch`; any single download
  failure throws immediately (no skip-and-continue).
- `DefaultApplyService.apply()` — loops every fetch commit then every deletion
  quarantine; any failure rolls back everything committed so far and throws.
- `SyncOrchestrator.run()` — wraps the entire `execute()` call in one try/catch; any
  exception from either step above becomes `SyncOutcome.Failed`.

So success is only reachable when literally everything in the plan completed (or the
plan was empty to begin with). `fetchedPaths` and on-disk reality are therefore
guaranteed to agree whenever `isSuccess()` is true.

Note this is a strictly weaker (safer) dependency than the original, buggy version had:
the corrected formula does **not** depend on "every desired path ends up on disk after a
success" — only on "every fetch that was planned was actually committed." That
distinction matters because `FileEntry` carries a currently-unused
`SyncPolicy(required=false, ...)`; if the diff engine were ever changed to honor it and
skip an optional fetch, a desired-but-skipped path would never appear on disk, and the
old `desiredPaths`-based formula would have been wrong to assume otherwise. The new
formula has no such dependency.

This all-or-throw behavior is an invariant of the *current*
`DefaultDownloadService`/`DefaultApplyService` implementations, not of the
`DownloadService`/`ApplyService` port contracts themselves — a future parallel/partial-
failure implementation could break it silently. This is called out with a comment at the
point the ledger is written in `SkeinClientSession`, so a future change to either service
has a pointer back to the dependency.

## Components

### `skein-core` (pure, no I/O — unaffected tests keep passing)

- **`diff/DiffOptions.java`** — add `Optional<Set<RelativePath>> previouslyManaged`
  (default empty via existing factories). New factory:
  `DiffOptions.safe(Set<RelativePath> previouslyManaged)` →
  `extrasPolicy=QUARANTINE`, same managed segments as `defaults()`,
  `previouslyManaged=Optional.of(previouslyManaged)`.
  `defaults()` / `additiveOnly()` unchanged (field stays `Optional.empty()`, so their
  existing tests are unaffected).
- **`diff/DefaultDiffEngine.java`** — `planDeletion()` additionally requires
  `options.previouslyManaged().map(s -> s.contains(path)).orElse(true)` — when the
  field is absent (old presets) this is always `true`, preserving current behavior
  exactly; when present, only ledger members are deletion-eligible.
- **`sync/SyncEvent.java`** — `DiffComputed` gains a field: `Set<RelativePath>
  desiredPaths`. Additive change to an existing record; nothing that pattern-matches
  `DiffComputed` today destructures it positionally, so no existing listener breaks.
- **`sync/SyncOrchestrator.java`** — the `DiffComputed` publish call passes
  `manifest.desiredByPath().keySet()` (the `manifest` local variable is already in
  scope at that point).

### `skein-bootstrap` (composition root — does plain I/O directly, same as `RootHashCache`)

- **New file `InstalledPathsCache.java`** — same shape as `RootHashCache.java`:
  `read()` → `Set<RelativePath>` (empty on missing/unparseable file, best-effort, never
  throws), `write(Set<RelativePath>)` (best-effort, never throws). Stored at
  `skeinDir().resolve("installed-paths.txt")`, one path per line, first line a literal
  `# skein-ledger v1` marker (any file not starting with that marker is treated as
  empty/absent rather than parsed, so a future format change fails safe instead of
  misreading).
- **`SyncComponents.java`** — `forSync(...)` takes an added
  `Set<RelativePath> previouslyManaged` parameter; builds
  `DiffOptions.safe(previouslyManaged)` instead of `DiffOptions.additiveOnly()`.
- **`SkeinClientSession.java`** — before building the orchestrator: read the ledger via
  `InstalledPathsCache`. Subscribe a second listener on the `EventBus` (alongside the
  existing forward-to-UI listener) that captures `DiffComputed.desiredPaths()`. After
  `orchestrator.run()`, if `outcome.isSuccess()`, write the captured set back — with a
  comment documenting the invariant above.

### Call sites that must be updated for the new `forSync` parameter

- `skein-cli/SkeinCli.java` — pass `Set.of()`. **Known limitation, not just "out of
  scope":** this is every call, not just the first, so the CLI path can never build a
  ledger no matter how many times it runs — it stays permanently on today's
  additive-only behavior. Both symptoms in Problem (orphaned renamed jar, mod removed
  from pack) persist indefinitely for anyone who syncs only via the CLI, never through
  a loader client. A CLI-side ledger (e.g. next to the CLI's target dir) would fix it,
  but is separate follow-up work, not part of this design.
- `skein-bootstrap/test/SyncComponentsTest.java` — add the new argument.

## Error handling / edge cases

- **Fresh install (no ledger file yet):** `read()` returns empty set → nothing is
  deletion-eligible on the first sync → identical to today's `additiveOnly()` behavior.
  Ledger starts getting written after the first successful sync.
- **Sync fails partway (download or apply error):** ledger is not written — next sync
  re-diffs against the last-known-good ledger, matching the fact that nothing on disk
  actually changed to the new plan.
- **Player deletes their Skein-installed mod manually, then a later sync removes it from
  the manifest too:** no-op — it's not on disk (`local.paths()` won't include it), so
  there's nothing to plan a deletion for regardless of ledger membership.
- **Ledger file corrupted / hand-edited / missing the version marker:** treated as empty
  (best-effort, same fallback style as `RootHashCache.read()`) — degrades to "nothing
  extra is removable this run," never to "remove everything." Fails safe.
- **Player renames/moves a Skein-installed mod file themselves:** the renamed file is
  untracked (not in the ledger under its new name) and the original path now looks
  locally-absent — a normal re-fetch (`ADD`) on the next sync, not a deletion bug. No new
  behavior here; this already happens today.

## Testing

Runnable in this environment (`javac` + standalone driver, or full JUnit if run through
Gradle later — the established pattern for shared modules):

- `DiffOptions` — new factory produces the expected `extrasPolicy`/`previouslyManaged`;
  `defaults()`/`additiveOnly()` still produce `Optional.empty()`.
- `DefaultDiffEngineTest` — new cases: (a) extra file **in** ledger + absent from
  manifest ⇒ planned `QUARANTINE` deletion; (b) extra file **not in** ledger ⇒ no
  deletion planned (player-owned file survives); existing cases using `defaults()` /
  `additiveOnly()` continue to pass unmodified.
- `SyncOrchestratorTest` — `DiffComputed` event now also carries `desiredPaths`;
  extend the existing fake-based test to assert its contents match the manifest's
  desired set.
- New `InstalledPathsCacheTest` (mirrors the shape of `RootHashCache`'s own tests) —
  round-trip read/write, missing file (never written) ⇒ empty, **unversioned file**
  (pre-dates this feature, e.g. hand-created or from a hypothetical older Skein build
  that predates the `# skein-ledger v1` marker) ⇒ empty, and **malformed-content file**
  (has the marker but garbage/unparseable lines after it) ⇒ empty — kept as two distinct
  cases rather than merged, since they're different failure modes that happen to share
  a fallback, and a future format migration only needs to change the "unversioned"
  case's expectation.
- Extend `SkeinClientSessionTest` (existing localhost-`HttpServer` integration test) —
  two-sync scenario: sync once against a manifest with mod A, then again against a
  manifest without mod A ⇒ A is quarantined; a player-added mod B present throughout
  ⇒ never touched. **Plus a failure-recovery case:** sync once successfully (ledger
  written), then a sync that fails partway (e.g. a download that 500s) against a
  manifest that dropped mod A ⇒ assert the ledger file on disk is byte-for-byte
  unchanged from before the failed attempt, then a third, successful sync still
  correctly quarantines A. This is the direct test for the "sync fails partway" edge
  case above, which was previously only asserted in prose.
- **Regression case for the `desiredPaths`-as-ledger bug:** a file present on disk
  *before the first sync ever runs*, byte-identical to what the manifest declares (so
  the first sync's diff classifies it `unchanged` — desired, but never fetched), then a
  later sync that drops that path from the manifest ⇒ the file is NOT quarantined,
  because it was never actually installed by Skein. This is the direct test for the bug
  found in review: `desiredPaths` is not the same thing as "paths Skein installed."

## Known follow-ups (deliberately deferred, not blockers)

- **Quarantine retention/pruning.** This feature makes quarantine growth more active:
  every mod the pack drops or renames now gets permanently retained under
  `.skein/quarantine/<batch>/` (never hard-deleted, by existing project principle), and
  there is currently no mechanism to prune old quarantine batches. Previously, under
  blanket `additiveOnly()`, quarantine only grew when the deletion policy itself allowed
  it (rarely exercised in practice); this feature makes the ledger-gated `safe()` path —
  and therefore quarantine writes — the normal case for any pack that evolves over time.
  A pruning/retention policy (e.g. age-based or size-capped cleanup of old batches) is a
  reasonable follow-up but is out of scope for this design.
