# Skein quarantine retention/pruning — design

**Date:** 2026-08-11
**Status:** approved (design), pending implementation plan

## Problem

`DefaultFileManager` never hard-deletes a file it replaces or removes — `commit()` and
`quarantine()` both move the old file aside into
`<gameDir>/.skein/quarantine/<epochMillis>-<counter>/...` instead, so a commit is always
reversible. Nothing ever removes anything from that directory afterward; quarantine
batches accumulate forever.

This was a minor, rarely-exercised cost under the old blanket `additiveOnly()` deletion
policy (see `docs/superpowers/specs/2026-08-09-install-ledger-design.md`). The install
ledger feature made ledger-gated
deletion (`DiffOptions.safe(previouslyManaged)`) the *normal* sync path for any pack that
evolves over time — every mod a pack drops or renames now routinely produces a new
quarantine batch. Flagged as a deferred follow-up in that design's "Known follow-ups"
section. This design closes that gap.

## Goals

- Old quarantine batches eventually get deleted for real, so disk usage doesn't grow
  without bound over the lifetime of an install.
- A player who notices a file is missing has a reasonable window to recover it manually
  from `.skein/quarantine/` before it's gone for good — this isn't instant deletion.
- Runs automatically on every client sync, silently, best-effort — no action required
  from the player or server admin (matches `RootHashCache`/`InstalledPathsCache`'s
  existing best-effort, fire-and-forget style).
- Loader-agnostic: lives entirely in `skein-storage`/`skein-bootstrap`, so both Forge and
  NeoForge get it automatically with no loader-specific code.
- Verifiable in this environment (`javac` + standalone driver / JUnit) — no ForgeGradle
  toolchain required.

## Non-goals

- Any policy other than fixed age-based retention (no size cap, no count cap, no
  configurability). A single hardcoded retention constant is enough for the problem as
  stated; a config knob can be added later if it's ever actually needed.
- A manual/CLI "prune now" command. Automatic-on-sync covers the actual problem (client
  installs growing without bound); a manual trigger is a separate, smaller feature that
  can be added later without changing this design.
- Reclaiming space *within* a sync session beyond what pruning removes — e.g. no change
  to staging (`.skein/downloads`) cleanup, which is a separate, already-working area.
- Any change to the loader adapters (`skein-forge`, `skein-neoforge`).

## Approaches considered

1. **Age-based pruning, run once per sync session (chosen).** Delete any quarantine
   batch older than a fixed retention window (30 days) at the start of every
   `SkeinClientSession.run()`. Simple, matches the existing best-effort maintenance
   pattern (`RootHashCache`, `InstalledPathsCache`), and the batch directory naming
   (`<epochMillis>-<counter>`) already encodes exactly the timestamp pruning needs — no
   new metadata to write or maintain.
2. **Size-capped pruning** (delete oldest batches until total quarantine size is under a
   byte budget). Rejected for now: requires a recursive size walk of every batch on every
   session, more bookkeeping, and a harder-to-predict UX (a single large pack update
   could evict batches far sooner than a player expects). Age-based is simpler and
   sufficient for the stated problem; can be revisited if disk usage turns out to be a
   real complaint in practice.
3. **Manual/CLI-triggered pruning only.** Rejected as the primary mechanism: most players
   never touch the CLI, so this wouldn't actually solve the problem for the common case
   (client GUI sync). Could still be added later as a supplementary affordance, but
   that's separate follow-up work, not part of this design.

## Architecture / data flow

```
                     ┌─────────────────────────────────────────┐
                     │           SkeinClientSession.run()        │
                     └─────────────────────────────────────────┘
  1. paths = platform.paths()
                            │
  2. new DefaultFileManager(paths.gameDir())
         .pruneQuarantine(Instant.now(), QUARANTINE_RETENTION)   ← new, runs first,
                            │                                       every session,
                            │                                       best-effort
  3. ...existing flow unchanged: fast-path check, ledger read,
     orchestrator build/run, ledger write...
```

`pruneQuarantine(Instant now, Duration maxAge)` (new method on `DefaultFileManager`,
`skein-storage`):

- Lists the immediate children of `quarantineRoot`
  (`<gameDir>/.skein/quarantine/`). No-op if that directory doesn't exist yet.
- For each child whose name matches `^\d+-\d+$` (the existing `<epochMillis>-<counter>`
  batch-naming convention from `moveToQuarantine`), parses the leading `epochMillis`
  (guarded — see "Malformed epoch prefix" below). If
  `now.toEpochMilli() - epochMillis > maxAge.toMillis()`, recursively deletes that whole
  batch directory (all files and subdirectories under it).
- Any entry under `quarantineRoot` that does **not** match the naming pattern is left
  completely untouched — defense in depth, the same spirit as `resolveUnder`'s
  path-escape check elsewhere in this class: never touch something this code doesn't
  recognize as its own.
- **Symlink escape check, matching `resolveUnder`'s actual mechanism (not just its
  "spirit"):** a name matching `^\d+-\d+$` is still only a name — before recursively
  deleting it, resolve its real path (`Files.exists(entry, LinkOption.NOFOLLOW_LINKS)` to
  detect a symlink, then `entry.toRealPath()`) and verify it `startsWith(quarantineRoot)`,
  the same check `resolveUnder` already applies when placing files. An entry that's a
  symlink (or otherwise resolves outside `quarantineRoot`) is left alone, not followed,
  not deleted — `DefaultFileManager` never created a symlink there itself, so one
  existing means something else put it there and it's out of scope for this method to
  guess why.
- Best-effort: a failure deleting one batch (e.g. a transiently locked file) is caught
  and skipped so it can't stop the rest of the batches from being pruned. Listing
  `quarantineRoot` itself failing is also caught. The method never throws.

Call site: `SkeinClientSession.run(String, Optional<String>, Optional<String>)` — the
common entry point both public overloads funnel through. Add the prune call as the very
first thing, right after `PlatformPaths paths = platform.paths();`, before the up-to-date
fast-path check, so it runs on every session including fast-path hits (a fast-path join
still counts as "the game was run," which is the natural cadence for this housekeeping).

```java
private static final Duration QUARANTINE_RETENTION = Duration.ofDays(30);
...
PlatformPaths paths = platform.paths();
new DefaultFileManager(paths.gameDir()).pruneQuarantine(Instant.now(), QUARANTINE_RETENTION);
```

This mirrors how `RootHashCache`/`InstalledPathsCache` are already freshly constructed
from `paths.*Dir()` at the top of the same method, rather than being threaded through
`SyncComponents` (which stays scoped to building the sync *orchestrator*, per its own
"single composition root [for the sync engine]" doc comment).

`pruneQuarantine` is deliberately **not** added to the `core.port.FileManager` interface.
It's storage-module housekeeping orthogonal to the sync pipeline's actual contract
(staging/commit/quarantine/rollback) — the core sync algorithm, and any fake `FileManager`
used in `skein-core` tests, has no need to know pruning exists.

### Why it's safe to delete a batch outright (no further quarantine-of-quarantine)

Could pruning ever delete a batch a rollback still needs? No.
`DefaultApplyService.rollback()` only ever acts on `CommitReceipt`s produced by the
*same, still-in-progress* `apply()` call, held in a local `List<CommitReceipt> committed`
that doesn't outlive `SyncOrchestrator.run()`. By the time any *later* session's prune
runs — the earliest possible next opportunity — every batch already on disk belongs to a
sync that has fully finished, one way or another (committed successfully, or already
rolled back within its own `apply()` call). There is no "in-flight" batch a later
session's prune could ever observe.

The 30-day window exists purely to give a player time to notice something's missing and
manually recover it from `.skein/quarantine/` themselves before it's actually gone — not
because deletion at that point is otherwise unsafe.

## Components

### `skein-storage`

- **`file/DefaultFileManager.java`** — add `pruneQuarantine(Instant now, Duration
  maxAge)`. Uses the already-private `quarantineRoot` field; no change to the public
  `FileManager` port.

### `skein-bootstrap`

- **`SkeinClientSession.java`** — add the `QUARANTINE_RETENTION` constant and the prune
  call described above. No signature changes to `run(...)`; purely additive.

## Error handling / edge cases

- **`quarantineRoot` doesn't exist (fresh install, nothing quarantined yet):** no-op.
- **A batch directory fails to delete** (e.g. a file inside it is transiently locked,
  more of a Windows concern than others): that one batch is left in place and retried on
  the next session; other batches still get pruned this run.
- **An unrecognized entry sits directly under `quarantineRoot`** (anything not matching
  `<epochMillis>-<counter>`): never touched, regardless of age.
- **Malformed epoch prefix:** an entry's name matches `^\d+-\d+$` shape-wise but the
  leading number is too large for `Long.parseLong` (e.g. a hand-edited or corrupted
  name) and throws `NumberFormatException`. Caught **per entry** and treated exactly
  like a naming-pattern mismatch — "unrecognized, leave alone" — not allowed to escape
  and abort pruning for the other, valid batches in the same pass.
- **An entry matching the batch-naming pattern is actually a symlink (or otherwise
  resolves outside `quarantineRoot`):** left untouched and not followed/deleted, per the
  real-path check in Architecture — `DefaultFileManager` never creates such an entry
  itself, so pruning treats one as out of scope rather than guessing intent.
- **A player manually deletes files out of an old batch dir, leaving it empty or
  partially empty:** still matched by the naming pattern on the parent dir name alone, so
  it's still eligible for pruning once past the retention window — deleting an
  already-mostly-empty directory tree is harmless.
- **No sync has run in a very long time (many stale batches built up):** the next
  session's prune deletes all of them in one pass. This can add some I/O latency to that
  one session but is not a correctness concern; not throttled further (YAGNI — normal
  join cadence keeps the backlog small in practice).

## Testing

Runnable in this environment (`javac` + standalone driver, or full JUnit if run through
Gradle later):

- `DefaultFileManagerTest` — new cases:
  - a batch older than `maxAge` (including nested files/subdirectories) is fully deleted.
  - a batch within `maxAge` survives untouched.
  - an entry under `quarantineRoot` that doesn't match the batch-naming pattern is left
    alone regardless of age.
  - an entry named like a valid old batch (e.g. `1-1`) that is actually a symlink
    pointing outside `quarantineRoot` is left untouched and not followed — the target it
    points to is unaffected.
  - an entry whose name matches `^\d+-\d+$` but whose leading number overflows
    `Long.parseLong` is left alone (no exception escapes `pruneQuarantine`), and a
    genuinely old, valid batch alongside it is still pruned in the same call.
  - calling `pruneQuarantine` when `quarantineRoot` doesn't exist yet is a no-op (no
    exception).
  - an old batch and a young batch side by side: only the old one is removed.
- `SkeinClientSessionTest` — hand-create an old quarantine batch directory on disk before
  calling `run(...)` (name prefixed with a very small epoch value, e.g. `1-1`, so it's
  always older than 30 days relative to real "now") and assert it's gone afterward. This
  proves the wiring at the `SkeinClientSession` call site, not just the unit-level
  `DefaultFileManager` method in isolation.

## Known follow-ups (deliberately deferred, not blockers)

- **`SkeinCli` never prunes.** `SkeinCli.java`'s sync path builds its own
  `DefaultFileManager` (via `SyncComponents.forSync`) and therefore *creates* quarantine
  batches, but there is no `pruneQuarantine` call anywhere in it — so a CLI-only install
  accumulates quarantine batches without bound, which is precisely the growth problem
  this feature exists to solve, just left unaddressed for that one entry point. This
  mirrors the identical, already-documented limitation for the same file in
  `docs/superpowers/specs/2026-08-09-install-ledger-design.md` (`SkeinCli` passes
  `Set.of()` on every run and so can never build a ledger either): same root cause — the
  CLI path was out of scope for both features — and the same shape of fix if it's ever
  picked up, namely a prune call placed alongside the CLI's own sync call, the way
  `SkeinClientSession` does it.
- **Pruning runs synchronously on the session's critical path**, including the
  previously free `UpToDate` fast path, which now does real directory I/O before it can
  return. Measured at roughly 1.1 seconds for 500 accumulated stale batches in a single
  pass. This is not a problem today: no loader adapter (`skein-forge`/`skein-neoforge`)
  exists yet, so nothing has yet decided which thread calls
  `SkeinClientSession.run(...)`. But whoever writes that Composition Root must keep this
  call off the render thread — the same constraint the rest of sync already has to
  respect.
