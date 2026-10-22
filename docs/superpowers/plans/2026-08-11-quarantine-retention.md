# Skein Quarantine Retention/Pruning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Old quarantine batches under `.skein/quarantine/` get permanently deleted after a 30-day retention window, running automatically and silently on every client sync, so disk usage no longer grows without bound now that the install ledger feature made ledger-gated deletion the normal sync path.

**Architecture:** A new `pruneQuarantine(Instant now, Duration maxAge)` method on `DefaultFileManager` (skein-storage) walks the immediate children of its private `quarantineRoot`, recursively deletes any child whose `<epochMillis>-<counter>` name is older than `maxAge`, and is defensively best-effort throughout (never throws; skips anything that doesn't match the naming convention, resolves outside `quarantineRoot` via a symlink, or has an unparseable epoch prefix). `SkeinClientSession.run(...)` (skein-bootstrap) calls it once per session, right at the top, with a fixed `Duration.ofDays(30)` constant.

**Tech Stack:** Java 17, `java.nio.file`, JUnit 5 + AssertJ (existing test stack, no new dependencies).

## Global Constraints

- `pruneQuarantine` is **best-effort and must never throw** — a failure pruning one batch, or listing `quarantineRoot` at all, is caught and swallowed so it can never break the caller's sync.
- Retention is a **fixed constant, `Duration.ofDays(30)`** — no config, no size/count cap (design's explicit non-goal).
- `core.port.FileManager` (skein-core) is **not modified** — `pruneQuarantine` lives only on the concrete `DefaultFileManager` (skein-storage).
- Any entry under `quarantineRoot` that doesn't match the `<epochMillis>-<counter>` naming pattern this class itself writes (in `moveToQuarantine`) — including one whose epoch prefix overflows `Long.parseLong`, or one that's a symlink resolving outside `quarantineRoot` — must be left completely untouched, regardless of age.
- Loader-agnostic: all changes are in `skein-storage` and `skein-bootstrap`; no `skein-forge`/`skein-neoforge` changes.
- Verify with `javac --release 17` compile order (`skein-common` → `skein-core` → `skein-platform`/`skein-transfer`) plus Gradle module tests (`./gradlew :skein-storage:test`, `./gradlew :skein-bootstrap:test`) per `CLAUDE.md`.

---

### Task 1: `DefaultFileManager.pruneQuarantine`

**Files:**
- Modify: `skein-storage/src/main/java/com/pathongko/skein/storage/file/DefaultFileManager.java`
- Test: `skein-storage/src/test/java/com/pathongko/skein/storage/file/DefaultFileManagerTest.java`

**Interfaces:**
- Consumes: nothing new — uses the existing private `quarantineRoot` field (`<root>/.skein/quarantine`) already set in `DefaultFileManager`'s constructor.
- Produces: `public void pruneQuarantine(Instant now, Duration maxAge)` on `DefaultFileManager` — Task 2 calls this exact signature.

- [ ] **Step 1: Write the failing tests**

Add these imports to `DefaultFileManagerTest.java` (alongside the existing ones):

```java
import java.nio.file.LinkOption;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Assumptions;
```

Add these test methods to the `DefaultFileManagerTest` class (after `stagingPathStaysUnderRoot`):

```java
    @Test
    void pruneQuarantineRemovesABatchOlderThanMaxAge() throws Exception {
        DefaultFileManager fm = new DefaultFileManager(root);
        long oldMillis = Instant.now().minus(Duration.ofDays(31)).toEpochMilli();
        Path oldBatch = root.resolve(".skein").resolve("quarantine").resolve(oldMillis + "-1");
        Files.createDirectories(oldBatch);
        Files.writeString(oldBatch.resolve("orphan.jar"), "old");

        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(oldBatch)).isFalse();
    }

    @Test
    void pruneQuarantineKeepsABatchWithinMaxAge() throws Exception {
        DefaultFileManager fm = new DefaultFileManager(root);
        long recentMillis = Instant.now().minus(Duration.ofDays(1)).toEpochMilli();
        Path recentBatch = root.resolve(".skein").resolve("quarantine").resolve(recentMillis + "-1");
        Files.createDirectories(recentBatch);
        Files.writeString(recentBatch.resolve("orphan.jar"), "recent");

        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(recentBatch)).isTrue();
    }

    @Test
    void pruneQuarantineLeavesAnEntryThatDoesNotMatchTheBatchNamingPatternAlone() throws Exception {
        DefaultFileManager fm = new DefaultFileManager(root);
        Path notABatch = root.resolve(".skein").resolve("quarantine").resolve("not-a-batch");
        Files.createDirectories(notABatch);
        Files.writeString(notABatch.resolve("mystery.txt"), "leave me alone");

        fm.pruneQuarantine(Instant.now(), Duration.ZERO);

        assertThat(Files.exists(notABatch)).isTrue();
    }

    @Test
    void pruneQuarantineIsANoOpWhenQuarantineRootDoesNotExist() {
        DefaultFileManager fm = new DefaultFileManager(root);

        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(root.resolve(".skein").resolve("quarantine"))).isFalse();
    }

    @Test
    void pruneQuarantineOnlyRemovesTheOldBatchWhenAYoungOneIsAlsoPresent() throws Exception {
        DefaultFileManager fm = new DefaultFileManager(root);
        Path quarantineDir = root.resolve(".skein").resolve("quarantine");
        long oldMillis = Instant.now().minus(Duration.ofDays(60)).toEpochMilli();
        long youngMillis = Instant.now().minus(Duration.ofHours(1)).toEpochMilli();
        Path oldBatch = quarantineDir.resolve(oldMillis + "-1");
        Path youngBatch = quarantineDir.resolve(youngMillis + "-2");
        Files.createDirectories(oldBatch);
        Files.createDirectories(youngBatch);
        Files.writeString(oldBatch.resolve("old.jar"), "old");
        Files.writeString(youngBatch.resolve("young.jar"), "young");

        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(oldBatch)).isFalse();
        assertThat(Files.exists(youngBatch)).isTrue();
    }

    @Test
    void pruneQuarantineLeavesASymlinkedEntryAloneAndDoesNotFollowIt() throws Exception {
        Path outsideTarget = root.resolve("outside-target");
        Files.createDirectories(outsideTarget);
        Files.writeString(outsideTarget.resolve("secret.txt"), "keep-me");

        Path quarantineDir = root.resolve(".skein").resolve("quarantine");
        Files.createDirectories(quarantineDir);
        long oldMillis = Instant.now().minus(Duration.ofDays(60)).toEpochMilli();
        Path link = quarantineDir.resolve(oldMillis + "-1");
        try {
            Files.createSymbolicLink(link, outsideTarget);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            Assumptions.assumeTrue(false, "symlinks not supported/permitted in this environment: " + e);
            return;
        }

        DefaultFileManager fm = new DefaultFileManager(root);
        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.exists(outsideTarget.resolve("secret.txt"))).isTrue();
    }

    @Test
    void pruneQuarantineSkipsAnEntryWithAnUnparseableEpochPrefixButStillPrunesAGenuinelyOldBatch()
            throws Exception {
        Path quarantineDir = root.resolve(".skein").resolve("quarantine");
        // Shape-matches "\d+-\d+" but the leading number overflows Long.parseLong.
        Path overflowing = quarantineDir.resolve("99999999999999999999-1");
        Files.createDirectories(overflowing);
        Files.writeString(overflowing.resolve("weird.jar"), "weird");

        long oldMillis = Instant.now().minus(Duration.ofDays(60)).toEpochMilli();
        Path oldBatch = quarantineDir.resolve(oldMillis + "-2");
        Files.createDirectories(oldBatch);
        Files.writeString(oldBatch.resolve("old.jar"), "old");

        DefaultFileManager fm = new DefaultFileManager(root);
        fm.pruneQuarantine(Instant.now(), Duration.ofDays(30));

        assertThat(Files.exists(overflowing)).isTrue();
        assertThat(Files.exists(oldBatch)).isFalse();
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :skein-storage:test --tests "com.pathongko.skein.storage.file.DefaultFileManagerTest"`
Expected: FAIL — `pruneQuarantine` does not exist on `DefaultFileManager` (compile error).

- [ ] **Step 3: Implement `pruneQuarantine`**

Add these imports to `DefaultFileManager.java` (alongside the existing ones):

```java
import java.nio.file.DirectoryStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
```

Add this field, alongside the existing `batchCounter` field:

```java
    private static final Pattern BATCH_NAME = Pattern.compile("\\d+-\\d+");
```

Add this public method after `quarantine(RelativePath target)` and before `rollback(CommitReceipt receipt)`:

```java
    /**
     * Best-effort maintenance: permanently deletes quarantine batches older than
     * {@code maxAge}. Never throws — a failure pruning one batch, or listing
     * {@link #quarantineRoot} at all, is swallowed so it can't block the caller's
     * sync or the rest of the batches. An entry that doesn't match the
     * {@code <epochMillis>-<counter>} naming convention this class itself writes
     * (in {@link #moveToQuarantine}), whose epoch prefix isn't a parseable long,
     * or that resolves outside {@link #quarantineRoot} (e.g. a symlink), is left
     * completely untouched — this method only ever removes things it recognizes
     * as its own.
     */
    public void pruneQuarantine(Instant now, Duration maxAge) {
        if (!Files.isDirectory(quarantineRoot)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(quarantineRoot)) {
            for (Path entry : entries) {
                pruneBatchIfExpired(entry, now, maxAge);
            }
        } catch (IOException | RuntimeException e) {
            // best-effort maintenance; try again next session
        }
    }

    private void pruneBatchIfExpired(Path entry, Instant now, Duration maxAge) {
        String name = entry.getFileName().toString();
        if (!BATCH_NAME.matcher(name).matches()) {
            return;
        }
        long epochMillis;
        try {
            epochMillis = Long.parseLong(name.substring(0, name.indexOf('-')));
        } catch (NumberFormatException e) {
            return;
        }
        if (now.toEpochMilli() - epochMillis <= maxAge.toMillis()) {
            return;
        }
        try {
            if (Files.isSymbolicLink(entry)
                    || !entry.toRealPath().startsWith(quarantineRoot.toRealPath())) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        deleteRecursively(entry);
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(DefaultFileManager::deleteQuietly);
        } catch (IOException e) {
            // best-effort
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            // best-effort; leave it for a later prune pass
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :skein-storage:test --tests "com.pathongko.skein.storage.file.DefaultFileManagerTest"`
Expected: PASS (all existing tests plus the 7 new ones). If the symlink test reports **skipped** rather than passed, that's expected on a machine without symlink privileges (e.g. Windows without Developer Mode) — not a failure.

- [ ] **Step 5: Commit**

```bash
git add skein-storage/src/main/java/com/pathongko/skein/storage/file/DefaultFileManager.java skein-storage/src/test/java/com/pathongko/skein/storage/file/DefaultFileManagerTest.java
git commit -m "feat(storage): add best-effort quarantine pruning to DefaultFileManager"
```

---

### Task 2: Wire pruning into `SkeinClientSession`

**Files:**
- Modify: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinClientSession.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinClientSessionTest.java`

**Interfaces:**
- Consumes: `DefaultFileManager.pruneQuarantine(Instant now, Duration maxAge)` from Task 1 (same package tree, `com.pathongko.skein.storage.file.DefaultFileManager` — already imported elsewhere in `skein-bootstrap` by `SyncComponents.java`, confirming the module dependency already exists).
- Produces: nothing new consumed by later tasks — this is the last task in the plan.

- [ ] **Step 1: Write the failing test**

Add this test method to `SkeinClientSessionTest.java` (after `syncsFromAnEndpointWithoutAGrant`):

```java
    @Test
    void pruningRemovesAnOldQuarantineBatchDuringASync(@TempDir Path root) throws Exception {
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");
        try (LocalPackServer server = new LocalPackServer(packDir)) {
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);

            // A batch dir named with a tiny epoch value is always far older than the
            // 30-day retention window, relative to real "now" -- no need to control
            // the clock to prove this.
            Path oldBatch = gameDir.resolve(".skein").resolve("quarantine").resolve("1-1");
            Files.createDirectories(oldBatch);
            Files.writeString(oldBatch.resolve("ancient.jar"), "long gone");

            SessionGrantS2C grant = new SessionGrantS2C(server.endpoint(), "", "1.0.0", server.rootHash());
            SyncOutcome outcome = new SkeinClientSession(platform, e -> {}).run(grant);

            assertThat(outcome.isSuccess()).isTrue();
            assertThat(Files.exists(oldBatch)).isFalse();
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SkeinClientSessionTest.pruningRemovesAnOldQuarantineBatchDuringASync"`
Expected: FAIL — `Files.exists(oldBatch)` is still `true` (nothing prunes it yet).

- [ ] **Step 3: Wire the prune call**

Add these imports to `SkeinClientSession.java` (alongside the existing ones):

```java
import com.pathongko.skein.storage.file.DefaultFileManager;
import java.time.Duration;
import java.time.Instant;
```

Add this constant to the `SkeinClientSession` class, alongside the class's other members:

```java
    private static final Duration QUARANTINE_RETENTION = Duration.ofDays(30);
```

In the `run(String transferEndpoint, Optional<String> token, Optional<String> expectedRootHash)` method, change:

```java
        PlatformPaths paths = platform.paths();
        RootHashCache rootHashCache = new RootHashCache(paths.skeinDir());
```

to:

```java
        PlatformPaths paths = platform.paths();
        new DefaultFileManager(paths.gameDir()).pruneQuarantine(Instant.now(), QUARANTINE_RETENTION);

        RootHashCache rootHashCache = new RootHashCache(paths.skeinDir());
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SkeinClientSessionTest"`
Expected: PASS (all existing `SkeinClientSessionTest` cases plus the new one).

- [ ] **Step 5: Run the full shared-module test suite**

Run: `./gradlew build`
Expected: all shared-module tests green (matches `CLAUDE.md`'s documented baseline; `skein-forge`/`skein-neoforge` stay uncompiled/commented out as already established).

- [ ] **Step 6: Commit**

```bash
git add skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinClientSession.java skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinClientSessionTest.java
git commit -m "feat(sync): prune old quarantine batches once per client sync session"
```
