# Install Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let client sync quarantine a local file only when Skein itself installed it in a previous sync, so a mod dropped or renamed by a pack update gets cleaned up — while a file the player added themselves is still never touched, on any sync, ever.

**Architecture:** Persist the manifest's desired path set as of the last successful sync in a small text file under `skeinDir()` (mirrors the existing `RootHashCache` pattern exactly). `DiffOptions` grows an optional "previously managed" set that gates deletion eligibility in `DefaultDiffEngine`; `SyncOrchestrator` publishes the desired path set on its existing `DiffComputed` event so the composition root can capture it without new plumbing through `SyncOutcome`. `SkeinClientSession` reads the ledger before syncing and writes it back only on success — proven safe by `DefaultDownloadService`/`DefaultApplyService`'s all-or-throw structure.

**Tech Stack:** Java 17, JUnit 5 + AssertJ, JDK-only I/O (`java.nio.file`) — no new dependencies. Entirely in `skein-core`/`skein-bootstrap`/`skein-cli`, all Gradle-buildable in this environment.

**Spec:** `docs/superpowers/specs/2026-08-09-install-ledger-design.md`

## Global Constraints

- `skein-core` must never import `net.minecraft.*` and depends on nothing but `skein-common` — none of this work needs Minecraft, so this constraint is trivially satisfied.
- This feature touches zero loader-adapter code (`skein-forge`, `skein-neoforge`); do not touch those modules.
- No new external runtime dependencies — everything is JDK-only, matching the rest of the shared modules.
- Deletions are never a hard delete — quarantine only (existing `DefaultFileManager` behavior; unchanged by this work).
- Preserve the guarantee from commit `363ee1e`: a file the player added themselves is never touched by sync, under any circumstance. Every task's tests must not regress this.
- Records that hold collections use a compact constructor to defensively copy them (`Set.copyOf`/`List.copyOf`), matching existing domain types (`LocalIndex`, `SyncPlan`).
- `skein-core`, `skein-bootstrap`, `skein-app`, `skein-storage`, `skein-transfer`, `skein-cli` are all buildable with `./gradlew` in this environment (Java 17 toolchain auto-detected at `C:\Program Files\Java\jdk-17`) — run every "Run:" command below for real; do not skip verification.

---

### Task 1: `DiffOptions` — add the ledger field and `safe()` factory

**Files:**
- Modify: `skein-core/src/main/java/com/pathongko/skein/core/diff/DiffOptions.java`
- Test: `skein-core/src/test/java/com/pathongko/skein/core/diff/DiffOptionsTest.java` (new)

**Interfaces:**
- Consumes: nothing new.
- Produces: `DiffOptions.previouslyManaged()` → `Optional<Set<RelativePath>>`; `static DiffOptions DiffOptions.safe(Set<RelativePath> previouslyManaged)`. `defaults()`/`additiveOnly()` keep their existing signatures and behavior (their `previouslyManaged` is always `Optional.empty()`).

- [ ] **Step 1: Write the failing test**

```java
package com.pathongko.skein.core.diff;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.domain.DeletionPolicy;
import com.pathongko.skein.core.domain.RelativePath;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiffOptionsTest {

    @Test
    void defaultsHasNoLedger() {
        DiffOptions options = DiffOptions.defaults();
        assertThat(options.extrasPolicy()).isEqualTo(DeletionPolicy.QUARANTINE);
        assertThat(options.previouslyManaged()).isEmpty();
    }

    @Test
    void additiveOnlyHasNoLedger() {
        DiffOptions options = DiffOptions.additiveOnly();
        assertThat(options.extrasPolicy()).isEqualTo(DeletionPolicy.KEEP);
        assertThat(options.previouslyManaged()).isEmpty();
    }

    @Test
    void safeCarriesTheGivenLedger() {
        RelativePath tracked = new RelativePath("mods/a.jar");
        DiffOptions options = DiffOptions.safe(Set.of(tracked));

        assertThat(options.extrasPolicy()).isEqualTo(DeletionPolicy.QUARANTINE);
        assertThat(options.previouslyManaged()).contains(Set.of(tracked));
    }

    @Test
    void safeManagesTheStandardCategoryDirs() {
        DiffOptions options = DiffOptions.safe(Set.of());
        assertThat(options.manages("mods")).isTrue();
        assertThat(options.manages("config")).isTrue();
        assertThat(options.manages("saves")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-core:test --tests "com.pathongko.skein.core.diff.DiffOptionsTest"`
Expected: FAIL — compilation error (`DiffOptions.safe` and `previouslyManaged()` don't exist yet).

- [ ] **Step 3: Replace `DiffOptions.java` with this full content**

```java
package com.pathongko.skein.core.diff;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.DeletionPolicy;
import com.pathongko.skein.core.domain.RelativePath;

import java.util.Optional;
import java.util.Set;

/**
 * Controls how extra local files (those not in the manifest) are treated.
 *
 * <p>{@code managedTopSegments} is the safety fence: only files whose top path
 * segment is in this set (e.g. "mods", "config") are ever considered for
 * deletion. Anything else the player has is left completely alone. Files under a
 * managed segment but flagged elsewhere as client-only are still kept.
 *
 * <p>{@code previouslyManaged}, when present, is a second fence: an extra file is
 * only deletion-eligible if it is also a member of this set — paths Skein itself
 * installed as of the last successful sync (design doc,
 * docs/superpowers/specs/2026-08-09-install-ledger-design.md). Absent means "no
 * ledger tracking": the original, ledger-unaware behavior of {@link #defaults()}
 * and {@link #additiveOnly()}, unchanged for backward compatibility.
 */
public record DiffOptions(
        DeletionPolicy extrasPolicy,
        Set<String> managedTopSegments,
        Optional<Set<RelativePath>> previouslyManaged) {

    private static final Set<String> STANDARD_MANAGED_SEGMENTS =
            Set.of("mods", "config", "resourcepacks", "shaderpacks", "datapacks");

    public DiffOptions {
        Preconditions.requireNonNull(extrasPolicy, "extrasPolicy");
        Preconditions.requireNonNull(managedTopSegments, "managedTopSegments");
        Preconditions.requireNonNull(previouslyManaged, "previouslyManaged");
        managedTopSegments = Set.copyOf(managedTopSegments);
        previouslyManaged = previouslyManaged.map(Set::copyOf);
    }

    /** Conservative default: quarantine extras only under the standard managed dirs. */
    public static DiffOptions defaults() {
        return new DiffOptions(DeletionPolicy.QUARANTINE, STANDARD_MANAGED_SEGMENTS, Optional.empty());
    }

    /** Never remove anything the player added; only add/update. */
    public static DiffOptions additiveOnly() {
        return new DiffOptions(DeletionPolicy.KEEP, Set.of(), Optional.empty());
    }

    /**
     * Quarantine extras under the standard managed dirs, but only when they are
     * also in {@code previouslyManaged} — paths Skein itself installed as of the
     * last successful sync. A file the player added is never in that set, so it is
     * never touched, no matter how long it sits under a managed dir.
     */
    public static DiffOptions safe(Set<RelativePath> previouslyManaged) {
        return new DiffOptions(DeletionPolicy.QUARANTINE, STANDARD_MANAGED_SEGMENTS, Optional.of(previouslyManaged));
    }

    public boolean manages(String topSegment) {
        return managedTopSegments.contains(topSegment);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :skein-core:test --tests "com.pathongko.skein.core.diff.DiffOptionsTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the full `skein-core` test suite to confirm nothing else broke**

Run: `./gradlew :skein-core:test`
Expected: PASS (all existing tests green — `defaults()`/`additiveOnly()` callers are unaffected since their behavior didn't change).

- [ ] **Step 6: Commit**

```bash
git add skein-core/src/main/java/com/pathongko/skein/core/diff/DiffOptions.java \
        skein-core/src/test/java/com/pathongko/skein/core/diff/DiffOptionsTest.java
git commit -m "$(cat <<'EOF'
feat(sync): add a ledger-aware DiffOptions.safe()

DiffOptions grows an optional previouslyManaged path set. Absent, existing
behavior (defaults()/additiveOnly()) is unchanged. The new safe() factory
will gate deletion eligibility on ledger membership in DefaultDiffEngine
(next task), so an extra file is only removable if Skein itself installed
it in a previous sync.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `DefaultDiffEngine` — gate deletions on ledger membership

**Files:**
- Modify: `skein-core/src/main/java/com/pathongko/skein/core/diff/DefaultDiffEngine.java`
- Modify: `skein-core/src/test/java/com/pathongko/skein/core/diff/DefaultDiffEngineTest.java`

**Interfaces:**
- Consumes: `DiffOptions.previouslyManaged()` from Task 1.
- Produces: no new public surface — `DefaultDiffEngine.diff(...)` keeps its existing signature; only its deletion-planning behavior changes when `previouslyManaged` is present.

- [ ] **Step 1: Add the failing tests to `DefaultDiffEngineTest.java`**

Add these two `@Test` methods to the existing class (after `additiveOnlyNeverDeletes`):

```java
    @Test
    void extraFileInLedgerIsPlannedForDeletion() {
        FileEntry a = mod("mods/a.jar", hash('a'));
        LocalIndex local = new LocalIndex(Map.of(
                new RelativePath("mods/a.jar"), hash('a'),
                new RelativePath("mods/orphan.jar"), hash('f')));

        DiffOptions options = DiffOptions.safe(Set.of(new RelativePath("mods/orphan.jar")));
        SyncPlan plan = engine.diff(manifest(a), local, options);

        assertThat(plan.deletions()).hasSize(1);
        assertThat(plan.deletions().get(0).path()).isEqualTo(new RelativePath("mods/orphan.jar"));
        assertThat(plan.deletions().get(0).policy()).isEqualTo(DeletionPolicy.QUARANTINE);
    }

    @Test
    void extraFileNotInLedgerIsKept() {
        FileEntry a = mod("mods/a.jar", hash('a'));
        LocalIndex local = new LocalIndex(Map.of(
                new RelativePath("mods/a.jar"), hash('a'),
                new RelativePath("mods/playerMod.jar"), hash('f')));

        // The ledger tracks a.jar only — playerMod.jar was never installed by
        // Skein, so it must survive even though it's under a managed dir.
        DiffOptions options = DiffOptions.safe(Set.of(new RelativePath("mods/a.jar")));
        SyncPlan plan = engine.diff(manifest(a), local, options);

        assertThat(plan.deletions()).isEmpty();
    }
```

(`DiffOptions`, `Set`, `DeletionPolicy` are already imported in this file — no import changes needed.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :skein-core:test --tests "com.pathongko.skein.core.diff.DefaultDiffEngineTest"`
Expected: FAIL — `extraFileInLedgerIsPlannedForDeletion` fails (no deletion planned, since `planDeletion` doesn't consult the ledger yet); `extraFileNotInLedgerIsKept` passes by accident (nothing is deleted today regardless). Confirm the first one fails before moving on.

- [ ] **Step 3: Update `planDeletion` in `DefaultDiffEngine.java`**

Modify `skein-core/src/main/java/com/pathongko/skein/core/diff/DefaultDiffEngine.java:62-69`, replacing:

```java
    private Optional<PlannedDeletion> planDeletion(RelativePath path, DiffOptions options) {
        boolean managed = options.manages(path.topSegment());
        boolean removable = options.extrasPolicy() != DeletionPolicy.KEEP;
        if (managed && removable) {
            return Optional.of(new PlannedDeletion(path, options.extrasPolicy()));
        }
        return Optional.empty();
    }
```

with:

```java
    private Optional<PlannedDeletion> planDeletion(RelativePath path, DiffOptions options) {
        boolean managed = options.manages(path.topSegment());
        boolean removable = options.extrasPolicy() != DeletionPolicy.KEEP;
        boolean ledgerAllows = options.previouslyManaged()
                .map(ledger -> ledger.contains(path))
                .orElse(true);
        if (managed && removable && ledgerAllows) {
            return Optional.of(new PlannedDeletion(path, options.extrasPolicy()));
        }
        return Optional.empty();
    }
```

Also update the class javadoc list at the top of the file (lines 21-27) so it stays accurate — replace:

```java
 *   <li>local &amp; not desired -&gt; deletion, but only under a managed top segment
 *       and only when the extras policy is not KEEP</li>
```

with:

```java
 *   <li>local &amp; not desired -&gt; deletion, but only under a managed top segment,
 *       only when the extras policy is not KEEP, and (when a ledger is configured)
 *       only if the path was previously Skein-managed</li>
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :skein-core:test --tests "com.pathongko.skein.core.diff.DefaultDiffEngineTest"`
Expected: PASS (8 tests — the 6 existing plus the 2 new ones). The existing `extraFileUnderManagedDirIsPlannedForDeletion` test (uses `DiffOptions.defaults()`, no ledger) must still pass unmodified — that's the backward-compatibility check.

- [ ] **Step 5: Commit**

```bash
git add skein-core/src/main/java/com/pathongko/skein/core/diff/DefaultDiffEngine.java \
        skein-core/src/test/java/com/pathongko/skein/core/diff/DefaultDiffEngineTest.java
git commit -m "$(cat <<'EOF'
feat(sync): gate DefaultDiffEngine deletions on ledger membership

planDeletion() now additionally requires previouslyManaged().contains(path)
when a ledger is configured (DiffOptions.safe). Absent, behavior is
unchanged. This is the core rule: an extra file is only removable if Skein
itself installed it, never because a player happened to put something
under mods/.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `SyncEvent.DiffComputed` carries the manifest's desired paths

**Files:**
- Modify: `skein-core/src/main/java/com/pathongko/skein/core/sync/SyncEvent.java`
- Modify: `skein-core/src/main/java/com/pathongko/skein/core/sync/SyncOrchestrator.java:86-88`
- Modify: `skein-core/src/test/java/com/pathongko/skein/core/sync/SyncOrchestratorTest.java`

**Interfaces:**
- Consumes: `manifest.desiredByPath()` (existing `ResolvedManifest` method).
- Produces: `SyncEvent.DiffComputed.desiredPaths()` → `Set<RelativePath>`. This is what Task 6's `SkeinClientSession` will capture and persist.

- [ ] **Step 1: Add the failing test to `SyncOrchestratorTest.java`**

Add this `@Test` method to the existing class:

```java
    @Test
    void diffComputedCarriesTheManifestsDesiredPaths() {
        FileEntry mod = file("mods/a.jar", FileCategory.MOD, hash('a'));

        orchestrator(manifest(FileCategory.MOD, mod), LocalIndex.empty(), CancellationToken.none()).run();

        assertThat(events)
                .filteredOn(e -> e instanceof SyncEvent.DiffComputed)
                .singleElement()
                .satisfies(e -> assertThat(((SyncEvent.DiffComputed) e).desiredPaths())
                        .containsExactly(new RelativePath("mods/a.jar")));
    }
```

(`RelativePath` is already imported in this file.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-core:test --tests "com.pathongko.skein.core.sync.SyncOrchestratorTest"`
Expected: FAIL — compilation error (`DiffComputed.desiredPaths()` doesn't exist yet).

- [ ] **Step 3: Replace `SyncEvent.java` with this full content**

```java
package com.pathongko.skein.core.sync;

import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.domain.RelativePath;

import java.util.Set;

/**
 * Something observable that happened during a sync. Publishing these (rather than
 * calling the UI/logger directly) is what makes progress display, logging and
 * metrics mere subscribers — add a new listener without touching the engine
 * (design doc, Part 5, Observer pattern).
 */
public sealed interface SyncEvent {

    record StateChanged(SyncState from, SyncState to) implements SyncEvent {
    }

    record ManifestResolved(String packId, PackVersion version) implements SyncEvent {
    }

    record DiffComputed(int fetches, int deletions, long bytesToDownload, Set<RelativePath> desiredPaths)
            implements SyncEvent {
        public DiffComputed {
            desiredPaths = Set.copyOf(desiredPaths);
        }
    }

    record ProgressUpdated(long bytesDone, long bytesTotal) implements SyncEvent {
    }

    record SyncFinished(SyncOutcome outcome) implements SyncEvent {
    }
}
```

- [ ] **Step 4: Update the publish call in `SyncOrchestrator.java`**

Modify `skein-core/src/main/java/com/pathongko/skein/core/sync/SyncOrchestrator.java:86-88`, replacing:

```java
        SyncPlan plan = diffEngine.diff(manifest, local, diffOptions);
        events.publish(new SyncEvent.DiffComputed(
                plan.fetches().size(), plan.deletions().size(), plan.bytesToDownload()));
```

with:

```java
        SyncPlan plan = diffEngine.diff(manifest, local, diffOptions);
        events.publish(new SyncEvent.DiffComputed(
                plan.fetches().size(), plan.deletions().size(), plan.bytesToDownload(),
                manifest.desiredByPath().keySet()));
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :skein-core:test --tests "com.pathongko.skein.core.sync.SyncOrchestratorTest"`
Expected: PASS (6 tests — the 5 existing plus the new one).

- [ ] **Step 6: Run the full `skein-core` test suite and the `skein-cli`/`skein-bootstrap` compile check**

Run: `./gradlew :skein-core:test :skein-bootstrap:compileJava :skein-bootstrap:compileTestJava :skein-cli:compileJava`
Expected: PASS — `SkeinProgressScreen`-equivalent event consumers elsewhere (`SkeinCli.printEvent`, the bootstrap tests) only call `.fetches()`/`.deletions()`/`.bytesToDownload()` on `DiffComputed`, never positionally destructure it, so the added field doesn't break them. (`skein-forge`'s `SkeinProgressScreen` also only calls accessor methods, but that module isn't in the Gradle build here — nothing to compile-check for it.)

- [ ] **Step 7: Commit**

```bash
git add skein-core/src/main/java/com/pathongko/skein/core/sync/SyncEvent.java \
        skein-core/src/main/java/com/pathongko/skein/core/sync/SyncOrchestrator.java \
        skein-core/src/test/java/com/pathongko/skein/core/sync/SyncOrchestratorTest.java
git commit -m "$(cat <<'EOF'
feat(sync): publish the manifest's desired paths on DiffComputed

Additive field on an existing record — every current listener calls
accessor methods, not positional destructuring, so nothing breaks. This
is how the composition root (next tasks) will learn what to persist as
the new install ledger, without adding a return value to SyncOutcome or
a new port to SyncOrchestrator.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `InstalledPathsCache` — the persisted ledger

**Files:**
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/InstalledPathsCache.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/InstalledPathsCacheTest.java`

**Interfaces:**
- Consumes: nothing new (mirrors `RootHashCache`'s existing shape: `Path skeinDir` constructor arg).
- Produces: `new InstalledPathsCache(Path skeinDir)`; `Set<RelativePath> read()`; `void write(Set<RelativePath> installedPaths)`. Both never throw (best-effort, same contract as `RootHashCache`).

- [ ] **Step 1: Write the failing tests**

```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.pathongko.skein.core.domain.RelativePath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledPathsCacheTest {

    @Test
    void emptyWhenNeverWritten(@TempDir Path dir) {
        assertThat(new InstalledPathsCache(dir.resolve("skein")).read()).isEmpty();
    }

    @Test
    void roundTrips(@TempDir Path dir) {
        InstalledPathsCache cache = new InstalledPathsCache(dir.resolve("skein"));
        Set<RelativePath> paths = Set.of(new RelativePath("mods/a.jar"), new RelativePath("config/x.toml"));

        cache.write(paths);

        assertThat(cache.read()).isEqualTo(paths);
    }

    @Test
    void emptySetRoundTrips(@TempDir Path dir) {
        InstalledPathsCache cache = new InstalledPathsCache(dir.resolve("skein"));

        cache.write(Set.of());

        assertThat(cache.read()).isEmpty();
    }

    @Test
    void unversionedFileIsTreatedAsEmpty(@TempDir Path dir) throws Exception {
        // Predates this feature (or hand-created): no "# skein-ledger v1" marker line.
        Path skein = dir.resolve("skein");
        Files.createDirectories(skein);
        Files.writeString(skein.resolve("installed-paths.txt"), "mods/a.jar\nmods/b.jar\n");

        assertThat(new InstalledPathsCache(skein).read()).isEmpty();
    }

    @Test
    void malformedContentIsTreatedAsEmpty(@TempDir Path dir) throws Exception {
        // Has *a* first line, but it's not valid UTF-8 text at all — a different
        // failure mode than "unversioned" even though the fallback is the same.
        Path skein = dir.resolve("skein");
        Files.createDirectories(skein);
        Files.write(skein.resolve("installed-paths.txt"), new byte[]{(byte) 0xFF, 0, 1, 2});

        assertThatCode(() -> new InstalledPathsCache(skein).read()).doesNotThrowAnyException();
        assertThat(new InstalledPathsCache(skein).read()).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.InstalledPathsCacheTest"`
Expected: FAIL — `InstalledPathsCache` doesn't exist yet (compilation error).

- [ ] **Step 3: Create `InstalledPathsCache.java`**

```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.RelativePath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists the set of paths Skein itself installed as of the last successful
 * sync, under skeinDir. Read before diffing and written after a successful sync
 * (see {@link SkeinClientSession}) so {@code DiffOptions.safe} can tell a
 * Skein-installed file apart from one the player added themselves.
 */
public final class InstalledPathsCache {

    private static final String VERSION_MARKER = "# skein-ledger v1";

    private final Path file;

    public InstalledPathsCache(Path skeinDir) {
        Preconditions.requireNonNull(skeinDir, "skeinDir");
        this.file = skeinDir.resolve("installed-paths.txt");
    }

    /** Best-effort: a missing file, an unversioned file, or any parse failure yields an empty set. */
    public Set<RelativePath> read() {
        try {
            if (!Files.isRegularFile(file)) {
                return Set.of();
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(VERSION_MARKER)) {
                return Set.of();
            }
            Set<RelativePath> paths = new LinkedHashSet<>();
            for (String line : lines.subList(1, lines.size())) {
                if (!line.isBlank()) {
                    paths.add(new RelativePath(line.trim()));
                }
            }
            return Set.copyOf(paths);
        } catch (IOException | RuntimeException e) {
            return Set.of();
        }
    }

    /** Best-effort: a write failure only costs an extra re-fetch/re-check next time, not a session failure. */
    public void write(Set<RelativePath> installedPaths) {
        Preconditions.requireNonNull(installedPaths, "installedPaths");
        try {
            Files.createDirectories(file.getParent());
            StringBuilder content = new StringBuilder(VERSION_MARKER).append('\n');
            installedPaths.stream()
                    .map(RelativePath::value)
                    .sorted()
                    .forEach(p -> content.append(p).append('\n'));
            Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // best-effort cache; see javadoc above
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.InstalledPathsCacheTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/InstalledPathsCache.java \
        skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/InstalledPathsCacheTest.java
git commit -m "$(cat <<'EOF'
feat(sync): add InstalledPathsCache, the persisted install ledger

Mirrors RootHashCache exactly: best-effort read/write under skeinDir,
never throws. Stores one relative path per line behind a "# skein-ledger
v1" marker so a pre-existing or hand-edited file fails safe (empty, never
"remove everything") instead of being misparsed.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `SyncComponents.forSync` takes the ledger; update its callers

**Files:**
- Modify: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SyncComponents.java`
- Modify: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SyncComponentsTest.java`
- Modify: `skein-cli/src/main/java/com/pathongko/skein/cli/SkeinCli.java`

**Interfaces:**
- Consumes: `DiffOptions.safe(Set<RelativePath>)` from Task 1.
- Produces: `SyncComponents.forSync(String endpoint, Path targetDir, Optional<String> token, EventBus events, Set<RelativePath> previouslyManaged)` → `SyncOrchestrator`. This replaces the 4-arg overload everywhere; there is no backward-compatible overload (only two call sites exist in the whole repo — `SkeinClientSession` (Task 6) and `SkeinCli` — both updated in this and the next task).

- [ ] **Step 1: Update the failing test in `SyncComponentsTest.java`**

Replace the whole file with:

```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.sync.SynchronousEventBus;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SyncComponentsTest {
    @Test
    void buildsAnOrchestratorWithoutTouchingTheNetwork(@org.junit.jupiter.api.io.TempDir Path dir) {
        var orch = SyncComponents.forSync(
                "http://127.0.0.1:1", dir, Optional.empty(), new SynchronousEventBus(), Set.of());
        assertThat(orch).isNotNull();
        assertThat(orch.currentState().name()).isEqualTo("IDLE");
    }

    @Test
    void categoryDirsAreTheCanonicalMap() {
        assertThat(SyncComponents.CATEGORY_DIRS)
                .containsEntry(FileCategory.MOD, "mods")
                .containsEntry(FileCategory.CONFIG, "config")
                .containsEntry(FileCategory.RESOURCEPACK, "resourcepacks")
                .containsEntry(FileCategory.SHADERPACK, "shaderpacks")
                .containsEntry(FileCategory.DATAPACK, "datapacks");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SyncComponentsTest"`
Expected: FAIL — compilation error (no 5-arg `forSync` overload yet).

- [ ] **Step 3: Update `SyncComponents.java`**

Modify `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SyncComponents.java`. Add an import and change the method signature and body.

Add this import alongside the existing ones (after `import com.pathongko.skein.core.domain.HashAlgorithm;`):

```java
import com.pathongko.skein.core.domain.RelativePath;
```

Add this import alongside the existing `java.util.*` imports (after `import java.util.Optional;`):

```java
import java.util.Set;
```

Replace lines 49-79 (the whole `forSync` method), currently:

```java
    public static SyncOrchestrator forSync(String endpoint, Path targetDir,
                                           Optional<String> token, EventBus events) {
        HashService hash = new MessageDigestHashService();
        JsonManifestCodec codec = new JsonManifestCodec();
        HttpRangeClient client = new JdkHttpRangeClient(
                HttpClient.newHttpClient(), Duration.ofSeconds(60), () -> token);

        ManifestResolver manifests = new HttpManifestResolver(client, codec, endpoint);
        // Scan every managed category dir so ADD/UPDATE are detected, but the diff
        // itself runs additive-only (see diffOptions below): the scanned segment set
        // is independent of the deletion policy.
        FileSystemLocalStateProvider localState = new FileSystemLocalStateProvider(
                targetDir, hash, HashAlgorithm.SHA256, SCANNED_TOP_SEGMENTS);
        FileManager fileManager = new DefaultFileManager(targetDir);
        FileDownloader downloader = new DefaultFileDownloader(client, hash,
                new DefaultRetryPolicy(4, new ExponentialBackoff(
                        Duration.ofMillis(500), Duration.ofSeconds(20), Jitter.full(Math::random))),
                Sleeper.real());
        DownloadService downloadService = new DefaultDownloadService(downloader, fileManager);
        ApplyService applyService = new DefaultApplyService(fileManager);

        // Additive-only: download/update everything the server pack declares, but never
        // remove a file the player has that isn't in the manifest. This keeps client-only
        // mods (minimaps, shaders, etc.) intact. Trade-off: a mod update that changes the
        // jar's filename leaves the old jar in place, so version-in-filename packs must
        // rely on the loader deduping or ship a manual cleanup — revisit when Skein tracks
        // an install receipt to safely remove only files it previously installed.
        return new SyncOrchestrator(manifests, localState, new DefaultDiffEngine(),
                downloadService, applyService, events, DiffOptions.additiveOnly(),
                CancellationToken.none());
    }
```

with:

```java
    public static SyncOrchestrator forSync(String endpoint, Path targetDir, Optional<String> token,
                                           EventBus events, Set<RelativePath> previouslyManaged) {
        HashService hash = new MessageDigestHashService();
        JsonManifestCodec codec = new JsonManifestCodec();
        HttpRangeClient client = new JdkHttpRangeClient(
                HttpClient.newHttpClient(), Duration.ofSeconds(60), () -> token);

        ManifestResolver manifests = new HttpManifestResolver(client, codec, endpoint);
        // Scan every managed category dir so ADD/UPDATE are detected; the scanned
        // segment set is independent of the deletion policy below.
        FileSystemLocalStateProvider localState = new FileSystemLocalStateProvider(
                targetDir, hash, HashAlgorithm.SHA256, SCANNED_TOP_SEGMENTS);
        FileManager fileManager = new DefaultFileManager(targetDir);
        FileDownloader downloader = new DefaultFileDownloader(client, hash,
                new DefaultRetryPolicy(4, new ExponentialBackoff(
                        Duration.ofMillis(500), Duration.ofSeconds(20), Jitter.full(Math::random))),
                Sleeper.real());
        DownloadService downloadService = new DefaultDownloadService(downloader, fileManager);
        ApplyService applyService = new DefaultApplyService(fileManager);

        // Ledger-gated: quarantine an extra file only when it's in previouslyManaged
        // (paths Skein itself installed as of the last successful sync — see
        // SkeinClientSession, which reads/writes the ledger around this call). A
        // file the player added is never in that set, so it's never touched. This
        // closes the gap plain additiveOnly() left: a mod dropped from the pack, or
        // renamed between versions, now gets cleaned up (design doc,
        // docs/superpowers/specs/2026-08-09-install-ledger-design.md).
        return new SyncOrchestrator(manifests, localState, new DefaultDiffEngine(),
                downloadService, applyService, events, DiffOptions.safe(previouslyManaged),
                CancellationToken.none());
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SyncComponentsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Fix the now-broken `skein-cli` build**

Modify `skein-cli/src/main/java/com/pathongko/skein/cli/SkeinCli.java`.

Add this import alongside the existing `java.util.*` imports (after `import java.util.Optional;`):

```java
import java.util.Set;
```

Replace lines 142-145, currently:

```java
        EventBus events = new SynchronousEventBus();
        events.subscribe(SkeinCli::printEvent);
        SyncOrchestrator orchestrator =
                SyncComponents.forSync(endpoint, targetDir, token, events);
```

with:

```java
        EventBus events = new SynchronousEventBus();
        events.subscribe(SkeinCli::printEvent);
        // Known limitation, not a bug: the CLI has no persisted ledger of its own
        // (unlike SkeinClientSession's InstalledPathsCache), so every run passes an
        // empty set here. It stays permanently on additive-only-equivalent behavior
        // — a mod dropped or renamed by a pack update is never cleaned up via the
        // CLI, no matter how many times it runs. See "Known limitation" in
        // docs/superpowers/specs/2026-08-09-install-ledger-design.md. A CLI-side
        // ledger is a reasonable follow-up but is separate work.
        SyncOrchestrator orchestrator =
                SyncComponents.forSync(endpoint, targetDir, token, events, Set.of());
```

- [ ] **Step 6: Verify `skein-cli` compiles**

Run: `./gradlew :skein-cli:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Run the full `skein-bootstrap` test suite**

Run: `./gradlew :skein-bootstrap:test`
Expected: PASS — note `SkeinClientSessionTest` and `SkeinClientSession.java` itself are not yet updated for the new `forSync` arity (that's Task 6), so this step will currently FAIL to compile in `skein-bootstrap`'s test sources unless Task 6 is done in the same pass. **If it fails to compile here, that's expected and fine — proceed directly to Task 6, which fixes it.** Do not attempt to make this step pass in isolation.

- [ ] **Step 8: Commit**

```bash
git add skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SyncComponents.java \
        skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SyncComponentsTest.java \
        skein-cli/src/main/java/com/pathongko/skein/cli/SkeinCli.java
git commit -m "$(cat <<'EOF'
feat(sync): wire SyncComponents.forSync to the install ledger

forSync() takes the caller's previouslyManaged set and builds
DiffOptions.safe(...) instead of the old additiveOnly(). Only two callers
exist: SkeinCli (updated here to pass Set.of() — a documented, permanent
limitation for the CLI path) and SkeinClientSession (next task, which
actually maintains the ledger).

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `SkeinClientSession` reads and writes the ledger

**Files:**
- Modify: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinClientSession.java`
- Modify: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinClientSessionTest.java`

**Interfaces:**
- Consumes: `InstalledPathsCache` (Task 4), `SyncComponents.forSync(..., Set<RelativePath>)` (Task 5), `SyncEvent.DiffComputed.desiredPaths()` (Task 3).
- Produces: `SkeinClientSession.run(...)` keeps both its existing signatures and its "never throws, returns `SyncOutcome`" contract — this task only changes what happens *inside* `run`.

- [ ] **Step 1: Add the failing tests to `SkeinClientSessionTest.java`**

Add this import alongside the existing ones:

```java
import com.pathongko.skein.core.domain.RelativePath;
```

Add these two `@Test` methods to the existing class:

```java
    @Test
    void secondSyncQuarantinesAModDroppedFromThePackButKeepsAPlayerAddedMod(@TempDir Path root) throws Exception {
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");
        try (LocalPackServer server = new LocalPackServer(packDir)) {
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);
            SessionGrantS2C grant = new SessionGrantS2C(server.endpoint(), "", "1.0.0", server.rootHash());

            SyncOutcome first = new SkeinClientSession(platform, e -> {}).run(grant);
            assertThat(first).isInstanceOf(SyncOutcome.RestartRequired.class);

            Path playerMod = gameDir.resolve("mods").resolve("player-added.jar");
            Files.createDirectories(playerMod.getParent());
            Files.writeString(playerMod, "not from the pack");

            server.pack().deleteFile(new RelativePath("mods/example.jar"));
            SessionGrantS2C secondGrant = new SessionGrantS2C(
                    server.endpoint(), "", "1.0.0", server.rootHash());

            SyncOutcome second = new SkeinClientSession(platform, e -> {}).run(secondGrant);

            assertThat(second.isSuccess()).isTrue();
            assertThat(Files.exists(gameDir.resolve("mods").resolve("example.jar"))).isFalse();
            assertThat(Files.exists(playerMod)).isTrue();
        }
    }

    @Test
    void ledgerSurvivesAFailedSyncAndAppliesCorrectlyOnTheNextSuccess(@TempDir Path root) throws Exception {
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");
        try (LocalPackServer server = new LocalPackServer(packDir)) {
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);
            SessionGrantS2C grant = new SessionGrantS2C(server.endpoint(), "", "1.0.0", server.rootHash());

            SyncOutcome first = new SkeinClientSession(platform, e -> {}).run(grant);
            assertThat(first.isSuccess()).isTrue();

            InstalledPathsCache ledger = new InstalledPathsCache(platform.paths().skeinDir());
            var afterFirstSync = ledger.read();
            assertThat(afterFirstSync).contains(new RelativePath("mods/example.jar"));

            // Admin removes example.jar, but this run points at an unreachable
            // server, forcing a failure (same trick as failsGracefullyOnUnreachableServer
            // below) — this simulates "sync fails partway": whatever the exact
            // failure point, the property under test is that a failed run must
            // never touch the ledger.
            server.pack().deleteFile(new RelativePath("mods/example.jar"));
            SyncOutcome failed = new SkeinClientSession(platform, e -> {})
                    .run("http://127.0.0.1:1", java.util.Optional.empty(), java.util.Optional.empty());
            assertThat(failed.isSuccess()).isFalse();
            assertThat(ledger.read()).isEqualTo(afterFirstSync);

            SessionGrantS2C thirdGrant = new SessionGrantS2C(
                    server.endpoint(), "", "1.0.0", server.rootHash());
            SyncOutcome third = new SkeinClientSession(platform, e -> {}).run(thirdGrant);

            assertThat(third.isSuccess()).isTrue();
            assertThat(Files.exists(gameDir.resolve("mods").resolve("example.jar"))).isFalse();
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SkeinClientSessionTest"`
Expected: FAIL to compile — `SkeinClientSession` still calls the old 4-arg `SyncComponents.forSync`, which no longer exists after Task 5. This is the expected state carried over from Task 5's Step 7.

- [ ] **Step 3: Replace `SkeinClientSession.java` with this full content**

```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.sync.EventBus;
import com.pathongko.skein.core.sync.SyncEvent;
import com.pathongko.skein.core.sync.SyncOrchestrator;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.core.sync.SynchronousEventBus;
import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.PlatformPaths;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one client sync session from a server-issued {@link SessionGrantS2C}:
 * fast-path check against the cached root hash, otherwise build and run the
 * orchestrator, republishing events to a listener. Never throws.
 */
public final class SkeinClientSession {

    private final ClientPlatform platform;
    private final SyncProgressListener listener;

    public SkeinClientSession(ClientPlatform platform, SyncProgressListener listener) {
        this.platform = Preconditions.requireNonNull(platform, "platform");
        this.listener = Preconditions.requireNonNull(listener, "listener");
    }

    public SyncOutcome run(SessionGrantS2C grant) {
        Preconditions.requireNonNull(grant, "grant");
        Optional<String> token = grant.sessionToken().isBlank()
                ? Optional.empty() : Optional.of(grant.sessionToken());
        return run(grant.transferEndpoint(), token, Optional.of(grant.rootManifestHash()));
    }

    /**
     * Sync directly from a resolved endpoint, with no play-phase grant. Used by
     * the disconnect-driven flow, which discovers the endpoint itself. When
     * {@code expectedRootHash} is present it enables the up-to-date fast path and
     * is cached on success; when absent, the diff runs unconditionally and the
     * cache is left untouched.
     */
    public SyncOutcome run(String transferEndpoint, Optional<String> token,
                           Optional<String> expectedRootHash) {
        Preconditions.requireNonBlank(transferEndpoint, "transferEndpoint");
        Preconditions.requireNonNull(token, "token");
        Preconditions.requireNonNull(expectedRootHash, "expectedRootHash");

        PlatformPaths paths = platform.paths();
        RootHashCache rootHashCache = new RootHashCache(paths.skeinDir());

        if (expectedRootHash.isPresent()
                && rootHashCache.read().filter(h -> h.equals(expectedRootHash.get())).isPresent()) {
            SyncOutcome upToDate = new SyncOutcome.UpToDate();
            try {
                listener.onEvent(new SyncEvent.SyncFinished(upToDate));
            } catch (RuntimeException ignored) {
                // listener is display code; its failure must not break the never-throws contract
            }
            return upToDate;
        }

        InstalledPathsCache installedCache = new InstalledPathsCache(paths.skeinDir());
        Set<RelativePath> previouslyManaged = installedCache.read();

        EventBus events = new SynchronousEventBus();
        events.subscribe(listener::onEvent);
        // Captures the manifest's desired path set as of this run's diff (published
        // before any download/apply I/O happens). Written back below only when the
        // outcome is a success — DefaultDownloadService/DefaultApplyService's
        // all-or-throw structure guarantees that "success" only happens when every
        // planned fetch was committed and every planned deletion was quarantined,
        // so this snapshot matches on-disk reality exactly whenever it's used. A
        // future DownloadService/ApplyService implementation that allows partial
        // completion without throwing would break that guarantee — re-check this
        // comment if either port grows a new implementation.
        AtomicReference<Set<RelativePath>> desiredPaths = new AtomicReference<>(Set.of());
        events.subscribe(event -> {
            if (event instanceof SyncEvent.DiffComputed diffComputed) {
                desiredPaths.set(diffComputed.desiredPaths());
            }
        });

        SyncOrchestrator orchestrator = SyncComponents.forSync(
                transferEndpoint, paths.gameDir(), token, events, previouslyManaged);

        SyncOutcome outcome = orchestrator.run();
        if (outcome.isSuccess()) {
            installedCache.write(desiredPaths.get());
            if (expectedRootHash.isPresent()) {
                try {
                    rootHashCache.write(expectedRootHash.get());
                } catch (IllegalArgumentException ignored) {
                    // best-effort cache; a blank hash from an otherwise-successful sync
                    // just costs a redundant diff next time, not a session failure
                }
            }
        }
        return outcome;
    }
}
```

(This is a one-instance-per-run session: no multi-profile concept exists anywhere in `PlatformPaths`/`RootHashCache`/this class, so one ledger file per `skeinDir()` — i.e. per client instance — is the existing architectural assumption, not a new constraint.)

- [ ] **Step 4: Run the new and existing `SkeinClientSessionTest` tests**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SkeinClientSessionTest"`
Expected: PASS (6 tests — the 4 existing plus the 2 new ones).

- [ ] **Step 5: Run the full `skein-bootstrap` and `skein-core` suites, plus the `skein-cli` compile check, together**

Run: `./gradlew :skein-core:test :skein-bootstrap:test :skein-cli:compileJava`
Expected: BUILD SUCCESSFUL, all tests green. This is the full regression check for every module this feature touches.

- [ ] **Step 6: Commit**

```bash
git add skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinClientSession.java \
        skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinClientSessionTest.java
git commit -m "$(cat <<'EOF'
feat(sync): SkeinClientSession reads and writes the install ledger

Reads InstalledPathsCache before building the orchestrator, captures
DiffComputed's desiredPaths via a second EventBus subscriber, and writes
it back only on a successful outcome. Closes the two symptoms from the
design doc: a mod renamed between pack versions no longer leaves an
orphaned jar, and a mod removed from the pack entirely now gets
quarantined client-side — while a player-added mod is still never
touched, on any sync, ever.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Final verification

- [ ] **Run the entire shared-module test suite one more time end to end**

Run: `./gradlew :skein-common:test :skein-core:test :skein-platform:test :skein-transfer:test :skein-storage:test :skein-app:test :skein-bootstrap:test :skein-cli:compileJava`
Expected: BUILD SUCCESSFUL, all tests green.

- [ ] **Confirm no loader-adapter files changed**

Run: `git diff --stat main...HEAD -- skein-forge skein-neoforge`
Expected: empty output (no changes under either loader module).
