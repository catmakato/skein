# Skein Loader Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the finished Skein sync engine actually run in Minecraft by adding the loader glue — a new shared, unit-tested `skein-bootstrap` module (composition root + handshake state machines) and a thin `skein-neoforge` adapter (NeoForge 1.20.1).

**Architecture:** Lift every loader-agnostic decision (composition root, client sync session, server handshake session) into `skein-bootstrap`, a pure-Java module verified here with JUnit + a `javac` driver. `skein-neoforge` is then reduced to mechanical `skein-platform` interface impls, one networking registration, and one progress `Screen` — the only code that touches `net.minecraft.*`, written here but compiled/run on the user's build machine.

**Tech Stack:** Java 17, Gradle (Kotlin DSL), JUnit 5 + AssertJ (shared modules), NeoGradle + NeoForge 47.1.106 + MC 1.20.1 (adapter, build machine only). Shared modules have zero external runtime deps.

## Global Constraints

- `skein-core` must never import `net.minecraft.*` and depends only on `skein-common`. (Unchanged here — we do not modify core.)
- `skein-bootstrap` must never import `net.minecraft.*`. It may depend on any shared module (`skein-platform`, `skein-core`, `skein-common`, `skein-app`, `skein-transfer`, `skein-storage`, `skein-server`). It is the composition layer.
- Packets cross the seam as `byte[]` via `skein-platform` codecs (`HandshakeReplyC2S.CODEC`, `SessionGrantS2C.CODEC`) — never `FriendlyByteBuf` in shared code.
- No hardcoded versions: adapter coordinates come from `gradle.properties` (`minecraftVersion=1.20.1`, `neoforgeVersion=47.1.106`, `neogradleVersion=7.0.145`) via `providers.gradleProperty(...)`.
- Category→directory map is the canonical one: MOD→`mods`, CONFIG→`config`, RESOURCEPACK→`resourcepacks`, SHADERPACK→`shaderpacks`, DATAPACK→`datapacks`.
- Every file under `skein-neoforge/src` that imports a loader or Minecraft API MUST begin with the comment `// UNVERIFIED: needs NeoGradle toolchain — compile on build machine`. These files are NOT compiled in the dev environment; never claim they pass.
- Shared-module verification convention: compile with `javac --release 17` (compile order common → core → platform/transfer/storage → server/app → bootstrap, putting earlier classes on `-cp`) and/or run the Gradle tests. A standalone driver `main` is the quick check.

---

## Reference: existing signatures this plan consumes

Copied verbatim so no task needs to re-discover them.

- `SyncOrchestrator(ManifestResolver, LocalStateProvider, DiffEngine, DownloadService, ApplyService, EventBus, DiffOptions, CancellationToken)`; `SyncOutcome run()` (never throws).
- `SyncOutcome` sealed: `UpToDate()`, `Applied()`, `RestartRequired()`, `Failed(ErrorCode, Recoverability, String detail)`, `Cancelled()`; all have `boolean isSuccess()`.
- `SyncEvent` sealed: `StateChanged(SyncState from, to)`, `ManifestResolved(String packId, PackVersion)`, `DiffComputed(int fetches, int deletions, long bytesToDownload)`, `ProgressUpdated(long bytesDone, long bytesTotal)`, `SyncFinished(SyncOutcome)`.
- `EventBus`: `void publish(SyncEvent)`, `Subscription subscribe(Consumer<SyncEvent>)`. Impl `SynchronousEventBus` (no-arg ctor).
- `CancellationToken` functional: `boolean isCancelled()`; `CancellationToken.none()`.
- Composition parts (all as used in `skein-cli/.../SkeinCli.java` `sync(...)`):
  - `new MessageDigestHashService()` → `HashService`
  - `new JsonManifestCodec()`; `byte[] encodeRoot(RootManifest)`, `RootManifest decodeRoot(byte[])`.
  - `new JdkHttpRangeClient(HttpClient, Duration, Supplier<Optional<String>> tokenSupplier)` → `HttpRangeClient`
  - `new HttpManifestResolver(HttpRangeClient, JsonManifestCodec, String baseUrl)` (baseUrl e.g. `http://host:port`; it appends `/manifest/root`).
  - `new FileSystemLocalStateProvider(Path targetDir, HashService, HashAlgorithm, int managedTopSegments)`
  - `new DefaultFileManager(Path targetDir)` → `FileManager`
  - `new DefaultFileDownloader(HttpRangeClient, HashService, RetryPolicy, Sleeper)`; `new DefaultRetryPolicy(int, ExponentialBackoff)`; `new ExponentialBackoff(Duration, Duration, Jitter)`; `Jitter.full(Math::random)`; `Sleeper.real()`.
  - `new DefaultDownloadService(FileDownloader, FileManager)` → `DownloadService`
  - `new DefaultApplyService(FileManager)` → `ApplyService`
  - `DiffOptions.defaults()`; `.managedTopSegments()` → int.
  - `new DefaultDiffEngine()` → `DiffEngine`.
- Domain: `HashAlgorithm.SHA256`; `FileCategory.{MOD,CONFIG,RESOURCEPACK,SHADERPACK,DATAPACK}`; `PackVersion.of(String)`, `PackVersion` has `toString()`.
- Server: `new SkeinHttpServer(Supplier<GeneratedPack>, Optional<String> token, Optional<AdminSurface>, InetSocketAddress)`; `int start()`; (there is also a 3-arg ctor without admin). `ManagedPack(Path packDir, String packId, PackVersion, HashAlgorithm, String baseUrl, Map<FileCategory,String>, HashService, JsonManifestCodec)`; `GeneratedPack current()`; `PackVersion version()`. `GeneratedPack.encodedRoot()` → `byte[]`, `root()` → `RootManifest`. `AdminSurface(ControlPlane, String password)`.
- Packets: `HandshakeReplyC2S(boolean hasMod, int clientProtocolVersion, String clientModVersion)` + `.CODEC`; `SessionGrantS2C(String transferEndpoint, String sessionToken, String packVersion, String rootManifestHash)` + `.CODEC`. Codec has `byte[] toBytes(T)` and `T fromBytes(byte[])`.
- Platform seam: `ClientPlatform{ loaderInfo(); paths(); gameThread(); controlChannel(); relauncher(); }`; `PlatformPaths{ gameDir(); configDir(); modsDir(); skeinDir(); }`; `GameThreadExecutor extends Executor`; `ClientRelauncher{ boolean canRelaunch(); void relaunch(); }`; `LoaderInfo{ loader(); minecraftVersion(); skeinVersion(); installedMods(); }`; `ControlChannel{ sendToServer(byte[]); sendToPlayer(PlayerRef, byte[]); onReceive(InboundHandler); }`, `InboundHandler.handle(PlayerRef sender, byte[])`; `PlayerRef.id()`.

---

## Part A — `skein-bootstrap` (verified in this environment)

### Task A1: Create the module and wire it into the build

**Files:**
- Create: `skein-bootstrap/build.gradle.kts`
- Modify: `settings.gradle.kts` (add `include("skein-bootstrap")` after `skein-server`)
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/package-info.java`
- Create: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/ModuleSmokeTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: a buildable `:skein-bootstrap` module on the classpath of later tasks.

- [ ] **Step 1: Add the module to settings**

In `settings.gradle.kts`, add after the `include("skein-server")` line:
```kotlin
include("skein-bootstrap")
```

- [ ] **Step 2: Write the build script**

The root `build.gradle.kts` already applies the `java` plugin, the Java 17 toolchain, `useJUnitPlatform()`, and `-Xlint:all` to every subproject. So a module script is **only** a `dependencies {}` block (mirror `skein-app/build.gradle.kts` and `skein-server/build.gradle.kts` — no `plugins`/`java`/`tasks.test`). Create `skein-bootstrap/build.gradle.kts`:
```kotlin
// skein-bootstrap: loader-agnostic composition root + client/server session state
// machines. Pure Java; no Minecraft. The loader adapters call into this module.

dependencies {
    implementation(project(":skein-common"))
    implementation(project(":skein-core"))
    implementation(project(":skein-platform"))
    implementation(project(":skein-app"))
    implementation(project(":skein-transfer"))
    implementation(project(":skein-storage"))
    implementation(project(":skein-server"))

    testImplementation(project(":skein-core"))
    testImplementation(project(":skein-platform"))
    testImplementation(project(":skein-server"))
    testImplementation(libs.bundles.testing)
}
```
Because these are `implementation` (not `api`) deps, a module that consumes `:skein-bootstrap` and also directly names types from `skein-server`/`skein-core`/etc. must declare those directly too (handled in Task A2 Step 5 for the CLI and Task B1 for NeoForge).

- [ ] **Step 3: Add a package marker**

Create `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/package-info.java`:
```java
/**
 * Loader-agnostic composition root and session state machines. Wires the shared
 * sync engine together and drives the client/server handshake. Contains NO
 * {@code net.minecraft.*} — each loader adapter hands it a {@code ClientPlatform}
 * / {@code ControlChannel} and this module does the rest.
 */
package com.pathongko.skein.bootstrap;
```

- [ ] **Step 4: Write the smoke test**

Create `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/ModuleSmokeTest.java`:
```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.domain.HashAlgorithm;
import org.junit.jupiter.api.Test;

class ModuleSmokeTest {
    @Test
    void sharedModulesAreOnTheClasspath() {
        assertThat(HashAlgorithm.SHA256).isNotNull();
    }
}
```

- [ ] **Step 5: Run the test**

Run: `./gradlew :skein-bootstrap:test`
Expected: PASS (1 test).

- [ ] **Step 6: Commit** (only if the repo is a git repo; it currently is not — skip commit steps until `git init` is run)

```bash
git add settings.gradle.kts skein-bootstrap
git commit -m "feat(bootstrap): scaffold skein-bootstrap module"
```

---

### Task A2: `SyncComponents` — the composition root factory

Extracts the wiring currently inlined in `SkeinCli.sync` into one reusable, testable factory. This is the DRY heart of the module.

**Files:**
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SyncComponents.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SyncComponentsTest.java`

**Interfaces:**
- Consumes: all composition parts from the Reference section.
- Produces:
  - `SyncComponents.forSync(String endpoint, Path targetDir, Optional<String> token, EventBus events)` → `SyncOrchestrator`
  - static `Map<FileCategory,String> CATEGORY_DIRS`

- [ ] **Step 1: Write the failing test**

Create `SyncComponentsTest.java`:
```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.sync.SynchronousEventBus;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SyncComponentsTest {
    @Test
    void buildsAnOrchestratorWithoutTouchingTheNetwork(@org.junit.jupiter.api.io.TempDir Path dir) {
        var orch = SyncComponents.forSync(
                "http://127.0.0.1:1", dir, Optional.empty(), new SynchronousEventBus());
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

- [ ] **Step 2: Run it, expect FAIL**

Run: `./gradlew :skein-bootstrap:test --tests "*SyncComponentsTest"`
Expected: FAIL — `SyncComponents` does not exist.

- [ ] **Step 3: Implement `SyncComponents`**

Create `SyncComponents.java` (this is exactly the wiring from `SkeinCli.sync`, factored out):
```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.app.apply.DefaultApplyService;
import com.pathongko.skein.app.download.DefaultDownloadService;
import com.pathongko.skein.app.local.FileSystemLocalStateProvider;
import com.pathongko.skein.app.manifest.HttpManifestResolver;
import com.pathongko.skein.core.diff.DefaultDiffEngine;
import com.pathongko.skein.core.diff.DiffOptions;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.port.FileManager;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.core.sync.CancellationToken;
import com.pathongko.skein.core.sync.EventBus;
import com.pathongko.skein.core.sync.SyncOrchestrator;
import com.pathongko.skein.core.sync.port.ApplyService;
import com.pathongko.skein.core.sync.port.DownloadService;
import com.pathongko.skein.core.sync.port.ManifestResolver;
import com.pathongko.skein.storage.file.DefaultFileManager;
import com.pathongko.skein.transfer.download.DefaultFileDownloader;
import com.pathongko.skein.transfer.download.FileDownloader;
import com.pathongko.skein.transfer.download.Sleeper;
import com.pathongko.skein.transfer.http.HttpRangeClient;
import com.pathongko.skein.transfer.http.JdkHttpRangeClient;
import com.pathongko.skein.transfer.retry.DefaultRetryPolicy;
import com.pathongko.skein.transfer.retry.ExponentialBackoff;
import com.pathongko.skein.transfer.retry.Jitter;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Assembles the shared sync engine from its parts. The single composition root. */
public final class SyncComponents {

    public static final Map<FileCategory, String> CATEGORY_DIRS = categoryDirs();

    private SyncComponents() {
    }

    public static SyncOrchestrator forSync(String endpoint, Path targetDir,
                                           Optional<String> token, EventBus events) {
        HashService hash = new MessageDigestHashService();
        JsonManifestCodec codec = new JsonManifestCodec();
        HttpRangeClient client = new JdkHttpRangeClient(
                HttpClient.newHttpClient(), Duration.ofSeconds(60), () -> token);

        ManifestResolver manifests = new HttpManifestResolver(client, codec, endpoint);
        FileSystemLocalStateProvider localState = new FileSystemLocalStateProvider(
                targetDir, hash, HashAlgorithm.SHA256, DiffOptions.defaults().managedTopSegments());
        FileManager fileManager = new DefaultFileManager(targetDir);
        FileDownloader downloader = new DefaultFileDownloader(client, hash,
                new DefaultRetryPolicy(4, new ExponentialBackoff(
                        Duration.ofMillis(500), Duration.ofSeconds(20), Jitter.full(Math::random))),
                Sleeper.real());
        DownloadService downloadService = new DefaultDownloadService(downloader, fileManager);
        ApplyService applyService = new DefaultApplyService(fileManager);

        return new SyncOrchestrator(manifests, localState, new DefaultDiffEngine(),
                downloadService, applyService, events, DiffOptions.defaults(),
                CancellationToken.none());
    }

    private static Map<FileCategory, String> categoryDirs() {
        Map<FileCategory, String> map = new EnumMap<>(FileCategory.class);
        map.put(FileCategory.MOD, "mods");
        map.put(FileCategory.CONFIG, "config");
        map.put(FileCategory.RESOURCEPACK, "resourcepacks");
        map.put(FileCategory.SHADERPACK, "shaderpacks");
        map.put(FileCategory.DATAPACK, "datapacks");
        return map;
    }
}
```

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew :skein-bootstrap:test --tests "*SyncComponentsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Refactor `SkeinCli.sync` to use it (DRY)**

In `skein-cli/build.gradle.kts` add `implementation(project(":skein-bootstrap"))` if not already reachable. In `SkeinCli.sync`, replace the manual wiring (the block building `HashService`…`SyncOrchestrator`) with:
```java
EventBus events = new SynchronousEventBus();
events.subscribe(SkeinCli::printEvent);
SyncOrchestrator orchestrator =
        com.pathongko.skein.bootstrap.SyncComponents.forSync(endpoint, targetDir, token, events);
```
Delete the now-unused imports. Keep `printEvent`, `describe`, and the `run()`/`System.exit` tail unchanged.

- [ ] **Step 6: Verify CLI still builds and syncs**

Run: `./gradlew :skein-cli:build`
Expected: PASS. (Optional manual check: run the demo `server` then `sync` as in README; outcome unchanged.)

- [ ] **Step 7: Commit**
```bash
git add skein-bootstrap skein-cli
git commit -m "feat(bootstrap): extract SyncComponents composition root; reuse in CLI"
```

---

### Task A3: `RootHashCache` — remember the last-applied root hash

Enables the up-to-date fast path (spec) and is trivially testable.

**Files:**
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/RootHashCache.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/RootHashCacheTest.java`

**Interfaces:**
- Produces: `RootHashCache(Path skeinDir)`; `Optional<String> read()`; `void write(String rootHash)`.

- [ ] **Step 1: Write the failing test**
```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RootHashCacheTest {
    @Test
    void emptyWhenAbsent(@TempDir Path dir) {
        assertThat(new RootHashCache(dir.resolve("skein")).read()).isEmpty();
    }

    @Test
    void roundTrips(@TempDir Path dir) {
        RootHashCache cache = new RootHashCache(dir.resolve("skein"));
        cache.write("abc123");
        assertThat(cache.read()).contains("abc123");
    }

    @Test
    void readIsSafeOnGarbage(@TempDir Path dir) throws Exception {
        Path skein = dir.resolve("skein");
        java.nio.file.Files.createDirectories(skein);
        java.nio.file.Files.write(skein.resolve("root.hash"), new byte[]{0, 1, 2});
        // The property under test is that a corrupt cache file never throws.
        org.assertj.core.api.Assertions
                .assertThatCode(() -> new RootHashCache(skein).read())
                .doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: Run it, expect FAIL**

Run: `./gradlew :skein-bootstrap:test --tests "*RootHashCacheTest"`
Expected: FAIL — `RootHashCache` missing.

- [ ] **Step 3: Implement**
```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Persists the root-manifest hash of the last successful sync under skeinDir. */
public final class RootHashCache {

    private final Path file;

    public RootHashCache(Path skeinDir) {
        Preconditions.requireNonNull(skeinDir, "skeinDir");
        this.file = skeinDir.resolve("root.hash");
    }

    public Optional<String> read() {
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public void write(String rootHash) {
        Preconditions.requireNonBlank(rootHash, "rootHash");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, rootHash, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // best-effort cache; a write failure only costs a redundant diff next time
        }
    }
}
```

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew :skein-bootstrap:test --tests "*RootHashCacheTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**
```bash
git add skein-bootstrap
git commit -m "feat(bootstrap): add RootHashCache for up-to-date fast path"
```

---

### Task A4: `SkeinClientSession` — the client state machine

**Files:**
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SyncProgressListener.java`
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinClientSession.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinClientSessionTest.java`
- Test helper: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/support/LocalPackServer.java`

**Interfaces:**
- Consumes: `SyncComponents.forSync(...)`, `RootHashCache`, `SessionGrantS2C`, `ClientPlatform`.
- Produces:
  - `SyncProgressListener` functional: `void onEvent(SyncEvent event)`.
  - `SkeinClientSession(ClientPlatform platform, SyncProgressListener listener)`.
  - `SyncOutcome run(SessionGrantS2C grant)` — never throws; runs synchronously on the calling thread (the caller decides threading).

- [ ] **Step 1: Write the progress-listener interface**

Create `SyncProgressListener.java`:
```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.core.sync.SyncEvent;

/** Receives sync events for display (progress screen, logs). */
@FunctionalInterface
public interface SyncProgressListener {
    void onEvent(SyncEvent event);
}
```

- [ ] **Step 2: Write a localhost pack server test helper**

Create `support/LocalPackServer.java` — starts a real `SkeinHttpServer` over a temp pack dir so the session can sync against it (the repo's established integration pattern). Build the served pack with `ManagedPack` exactly as `SkeinCli.server` does:
```java
package com.pathongko.skein.bootstrap.support;

import com.pathongko.skein.bootstrap.SyncComponents;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.server.http.SkeinHttpServer;
import com.pathongko.skein.server.manage.ManagedPack;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** A real Skein HTTP server over a temp pack dir, for end-to-end session tests. */
public final class LocalPackServer implements AutoCloseable {
    private final SkeinHttpServer server;
    private final ManagedPack pack;
    private final int port;

    public LocalPackServer(Path packDir) throws Exception {
        Files.createDirectories(packDir.resolve("mods"));
        Files.write(packDir.resolve("mods").resolve("example.jar"), "hello-mod".getBytes());
        String baseUrl = "http://127.0.0.1:0/files/";
        this.pack = new ManagedPack(packDir, "test-pack", PackVersion.of("1.0.0"),
                HashAlgorithm.SHA256, baseUrl, SyncComponents.CATEGORY_DIRS,
                new MessageDigestHashService(), new JsonManifestCodec());
        this.server = new SkeinHttpServer(pack::current, Optional.empty(),
                Optional.empty(), new InetSocketAddress("127.0.0.1", 0));
        this.port = server.start();
    }

    public String endpoint() {
        return "http://127.0.0.1:" + port;
    }

    public ManagedPack pack() {
        return pack;
    }

    public String rootHash() {
        return new MessageDigestHashService()
                .hash(pack.current().encodedRoot(), HashAlgorithm.SHA256).value();
    }

    @Override
    public void close() {
        server.stop(); // if SkeinHttpServer exposes stop(); otherwise adapt to its shutdown method
    }
}
```
Note: confirm `SkeinHttpServer` has a no-admin 3-arg ctor and a stop method by reading the class; adjust the two flagged lines to the real names. `FileHash.value()` is the hex string — confirm the accessor name in `FileHash` and use it.

- [ ] **Step 3: Write the failing session test**

Create `SkeinClientSessionTest.java`:
```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.bootstrap.support.LocalPackServer;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkeinClientSessionTest {

    @Test
    void syncsMissingModIntoModsDir(@TempDir Path root) throws Exception {
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");
        try (LocalPackServer server = new LocalPackServer(packDir)) {
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);
            List<Object> seen = new ArrayList<>();
            SkeinClientSession session = new SkeinClientSession(platform, seen::add);

            SyncOutcome outcome = session.run(new SessionGrantS2C(
                    server.endpoint(), "", "1.0.0", server.rootHash()));

            assertThat(outcome).isInstanceOf(SyncOutcome.RestartRequired.class);
            assertThat(Files.exists(gameDir.resolve("mods").resolve("example.jar"))).isTrue();
            assertThat(seen).isNotEmpty();
        }
    }

    @Test
    void secondRunTakesUpToDateFastPath(@TempDir Path root) throws Exception {
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");
        try (LocalPackServer server = new LocalPackServer(packDir)) {
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);
            SessionGrantS2C grant = new SessionGrantS2C(
                    server.endpoint(), "", "1.0.0", server.rootHash());
            new SkeinClientSession(platform, e -> {}).run(grant);

            SyncOutcome second = new SkeinClientSession(platform, e -> {}).run(grant);
            assertThat(second).isInstanceOf(SyncOutcome.UpToDate.class);
        }
    }

    @Test
    void failsGracefullyOnUnreachableServer(@TempDir Path root) {
        FakeClientPlatform platform = new FakeClientPlatform(root.resolve("client"));
        SyncOutcome outcome = new SkeinClientSession(platform, e -> {})
                .run(new SessionGrantS2C("http://127.0.0.1:1", "", "1.0.0", "deadbeef"));
        assertThat(outcome.isSuccess()).isFalse();
    }
}
```
Create the `FakeClientPlatform` test double in the same test package: a `ClientPlatform` whose `paths()` returns `gameDir`, `gameDir/config`, `gameDir/mods`, `gameDir/.skein`; `gameThread()` runs inline (`Runnable::run`); `controlChannel()`/`relauncher()`/`loaderInfo()` return simple stubs (a no-op `ControlChannel`, a `ClientRelauncher` returning `false`, a `LoaderInfo` with empty mod list). Write it as a small nested/standalone test class implementing those interfaces.

- [ ] **Step 4: Run it, expect FAIL**

Run: `./gradlew :skein-bootstrap:test --tests "*SkeinClientSessionTest"`
Expected: FAIL — `SkeinClientSession` missing.

- [ ] **Step 5: Implement `SkeinClientSession`**
```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.sync.EventBus;
import com.pathongko.skein.core.sync.SyncEvent;
import com.pathongko.skein.core.sync.SyncOrchestrator;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.core.sync.SynchronousEventBus;
import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.PlatformPaths;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import java.util.Optional;

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
        PlatformPaths paths = platform.paths();
        RootHashCache cache = new RootHashCache(paths.skeinDir());

        if (cache.read().filter(h -> h.equals(grant.rootManifestHash())).isPresent()) {
            SyncOutcome upToDate = new SyncOutcome.UpToDate();
            listener.onEvent(new SyncEvent.SyncFinished(upToDate));
            return upToDate;
        }

        EventBus events = new SynchronousEventBus();
        events.subscribe(listener::onEvent);

        Optional<String> token = grant.sessionToken().isBlank()
                ? Optional.empty() : Optional.of(grant.sessionToken());
        SyncOrchestrator orchestrator = SyncComponents.forSync(
                grant.transferEndpoint(), paths.gameDir(), token, events);

        SyncOutcome outcome = orchestrator.run();
        if (outcome.isSuccess()) {
            cache.write(grant.rootManifestHash());
        }
        return outcome;
    }
}
```

- [ ] **Step 6: Run tests, expect PASS**

Run: `./gradlew :skein-bootstrap:test --tests "*SkeinClientSessionTest"`
Expected: PASS (3 tests). If `SkeinHttpServer` ctor/stop or `FileHash.value()` names differ, fix the helper (Step 2 notes) and re-run.

- [ ] **Step 7: Commit**
```bash
git add skein-bootstrap
git commit -m "feat(bootstrap): SkeinClientSession with fast path and event republish"
```

---

### Task A5: `ClientBootstrap` — register the ControlChannel handler

**Files:**
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/ClientBootstrap.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/ClientBootstrapTest.java`

**Interfaces:**
- Produces:
  - `ClientBootstrap(ClientPlatform platform, java.util.concurrent.Executor syncExecutor, SyncProgressListener listener)`
  - `void install()` — registers an inbound handler on `platform.controlChannel()` that decodes `SessionGrantS2C` and runs a `SkeinClientSession` on `syncExecutor`.
  - `void sendHandshakeReply()` — sends `HandshakeReplyC2S(true, PROTOCOL_VERSION, modVersion)` to the server.
  - `static final int PROTOCOL_VERSION = 1;`

- [ ] **Step 1: Write the failing test**
```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.PlayerRef;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientBootstrapTest {

    @Test
    void handshakeReplyIsSentToServer(@TempDir java.nio.file.Path dir) {
        RecordingChannel channel = new RecordingChannel();
        FakeClientPlatform platform = new FakeClientPlatform(dir, channel);
        ClientBootstrap boot = new ClientBootstrap(platform, Runnable::run, e -> {});
        boot.install();
        boot.sendHandshakeReply();

        HandshakeReplyC2S reply = HandshakeReplyC2S.CODEC.fromBytes(channel.toServer.get(0));
        assertThat(reply.hasMod()).isTrue();
        assertThat(reply.clientProtocolVersion()).isEqualTo(ClientBootstrap.PROTOCOL_VERSION);
    }

    @Test
    void inboundGrantRunsASession(@TempDir java.nio.file.Path dir) throws Exception {
        RecordingChannel channel = new RecordingChannel();
        FakeClientPlatform platform = new FakeClientPlatform(dir, channel);
        CountDownLatch finished = new CountDownLatch(1);
        List<Object> events = new ArrayList<>();
        ClientBootstrap boot = new ClientBootstrap(platform, Runnable::run, ev -> {
            events.add(ev);
            finished.countDown();
        });
        boot.install();

        // Deliver a grant pointing at an unreachable endpoint: the session still
        // runs to a terminal (failed) outcome, which is all we assert here.
        channel.deliver(null, SessionGrantS2C.CODEC.toBytes(
                new SessionGrantS2C("http://127.0.0.1:1", "", "1.0.0", "x")));

        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).isNotEmpty();
    }
}
```
Reuse `FakeClientPlatform` from Task A4 but add a constructor taking an injected `ControlChannel`. `RecordingChannel` is a `ControlChannel` test double: `sendToServer` appends to a `List<byte[]> toServer`; `onReceive` stores the handler; `deliver(sender, bytes)` invokes it.

- [ ] **Step 2: Run it, expect FAIL**

Run: `./gradlew :skein-bootstrap:test --tests "*ClientBootstrapTest"`
Expected: FAIL — `ClientBootstrap` missing.

- [ ] **Step 3: Implement `ClientBootstrap`**
```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import java.util.concurrent.Executor;

/**
 * Client-side entry point the loader adapter calls. Registers the inbound handler
 * that turns a {@link SessionGrantS2C} into a background {@link SkeinClientSession}
 * run, and can send the handshake reply.
 */
public final class ClientBootstrap {

    public static final int PROTOCOL_VERSION = 1;

    private final ClientPlatform platform;
    private final Executor syncExecutor;
    private final SyncProgressListener listener;

    public ClientBootstrap(ClientPlatform platform, Executor syncExecutor,
                           SyncProgressListener listener) {
        this.platform = Preconditions.requireNonNull(platform, "platform");
        this.syncExecutor = Preconditions.requireNonNull(syncExecutor, "syncExecutor");
        this.listener = Preconditions.requireNonNull(listener, "listener");
    }

    public void install() {
        ControlChannel channel = platform.controlChannel();
        channel.onReceive((sender, payload) -> {
            SessionGrantS2C grant = SessionGrantS2C.CODEC.fromBytes(payload);
            syncExecutor.execute(() -> new SkeinClientSession(platform, listener).run(grant));
        });
    }

    public void sendHandshakeReply() {
        String modVersion = platform.loaderInfo().skeinVersion();
        HandshakeReplyC2S reply = new HandshakeReplyC2S(true, PROTOCOL_VERSION, modVersion);
        platform.controlChannel().sendToServer(HandshakeReplyC2S.CODEC.toBytes(reply));
    }
}
```

- [ ] **Step 4: Run tests, expect PASS**

Run: `./gradlew :skein-bootstrap:test --tests "*ClientBootstrapTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**
```bash
git add skein-bootstrap
git commit -m "feat(bootstrap): ClientBootstrap wires ControlChannel to sessions"
```

---

### Task A6: `ServerBootstrap` + `SkeinServerSession` — server handshake

**Files:**
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinServerSession.java`
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/ServerBootstrap.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/ServerHandshakeTest.java`

**Interfaces:**
- Produces:
  - `SkeinServerSession(String transferEndpoint, Optional<String> token, Supplier<GeneratedPack> pack, HashService hash)`; `SessionGrantS2C onHandshake(HandshakeReplyC2S reply)`.
  - `ServerBootstrap(ControlChannel channel, SkeinServerSession session)`; `void install()` — registers a handler that decodes `HandshakeReplyC2S`, computes the grant, and replies to the sending player.

- [ ] **Step 1: Write the failing test**
```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import com.pathongko.skein.server.manifest.GeneratedPack;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServerHandshakeTest {

    @Test
    void grantCarriesEndpointAndRootHash() {
        GeneratedPack pack = TestPacks.smallPack(); // helper building a GeneratedPack
        var hash = new MessageDigestHashService();
        SkeinServerSession session = new SkeinServerSession(
                "http://example:25585", Optional.of("tok"), () -> pack, hash);

        SessionGrantS2C grant = session.onHandshake(new HandshakeReplyC2S(true, 1, "0.0.1"));

        assertThat(grant.transferEndpoint()).isEqualTo("http://example:25585");
        assertThat(grant.sessionToken()).isEqualTo("tok");
        assertThat(grant.packVersion()).isEqualTo(pack.root().packVersion().toString());
        assertThat(grant.rootManifestHash())
                .isEqualTo(hash.hash(pack.encodedRoot(), HashAlgorithm.SHA256).value());
    }

    @Test
    void bootstrapRepliesToSender() {
        GeneratedPack pack = TestPacks.smallPack();
        RecordingChannel channel = new RecordingChannel();
        SkeinServerSession session = new SkeinServerSession(
                "http://e:1", Optional.empty(), () -> pack, new MessageDigestHashService());
        new ServerBootstrap(channel, session).install();

        channel.deliver(new NamedPlayer("p1"),
                HandshakeReplyC2S.CODEC.toBytes(new HandshakeReplyC2S(true, 1, "0.0.1")));

        assertThat(channel.toPlayer).hasSize(1);
        SessionGrantS2C grant = SessionGrantS2C.CODEC.fromBytes(channel.toPlayer.get(0).payload());
        assertThat(grant.transferEndpoint()).isEqualTo("http://e:1");
    }
}
```
Add to `RecordingChannel`: a `List<SentToPlayer> toPlayer` where `SentToPlayer(PlayerRef player, byte[] payload)`; implement `sendToPlayer(player, payload)` to append. `NamedPlayer` is a `PlayerRef` with a given `id()`. Create `TestPacks.smallPack()` in the test package: build a `GeneratedPack` by running the server's `ManifestGenerator` over a tmp dir, or by constructing `ManagedPack(...).current()` as in `LocalPackServer`.

- [ ] **Step 2: Run it, expect FAIL**

Run: `./gradlew :skein-bootstrap:test --tests "*ServerHandshakeTest"`
Expected: FAIL — types missing.

- [ ] **Step 3: Implement `SkeinServerSession`**
```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import com.pathongko.skein.server.manifest.GeneratedPack;
import java.util.Optional;
import java.util.function.Supplier;

/** Server-side handshake logic: turn a client reply into a session grant. */
public final class SkeinServerSession {

    private final String transferEndpoint;
    private final Optional<String> token;
    private final Supplier<GeneratedPack> pack;
    private final HashService hash;

    public SkeinServerSession(String transferEndpoint, Optional<String> token,
                              Supplier<GeneratedPack> pack, HashService hash) {
        this.transferEndpoint = Preconditions.requireNonBlank(transferEndpoint, "transferEndpoint");
        this.token = Preconditions.requireNonNull(token, "token");
        this.pack = Preconditions.requireNonNull(pack, "pack");
        this.hash = Preconditions.requireNonNull(hash, "hash");
    }

    public SessionGrantS2C onHandshake(HandshakeReplyC2S reply) {
        Preconditions.requireNonNull(reply, "reply");
        GeneratedPack current = pack.get();
        String rootHash = hash.hash(current.encodedRoot(), HashAlgorithm.SHA256).value();
        return new SessionGrantS2C(
                transferEndpoint,
                token.orElse(""),
                current.root().packVersion().toString(),
                rootHash);
    }
}
```

- [ ] **Step 4: Implement `ServerBootstrap`**
```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;

/** Registers the server-side handshake handler on a ControlChannel. */
public final class ServerBootstrap {

    private final ControlChannel channel;
    private final SkeinServerSession session;

    public ServerBootstrap(ControlChannel channel, SkeinServerSession session) {
        this.channel = Preconditions.requireNonNull(channel, "channel");
        this.session = Preconditions.requireNonNull(session, "session");
    }

    public void install() {
        channel.onReceive((sender, payload) -> {
            HandshakeReplyC2S reply = HandshakeReplyC2S.CODEC.fromBytes(payload);
            SessionGrantS2C grant = session.onHandshake(reply);
            channel.sendToPlayer(sender, SessionGrantS2C.CODEC.toBytes(grant));
        });
    }
}
```

- [ ] **Step 5: Run tests, expect PASS**

Run: `./gradlew :skein-bootstrap:test --tests "*ServerHandshakeTest"`
Expected: PASS (2 tests). Confirm `FileHash.value()` accessor name; fix if different.

- [ ] **Step 6: Commit**
```bash
git add skein-bootstrap
git commit -m "feat(bootstrap): server handshake session + ServerBootstrap"
```

---

### Task A7: Standalone `javac` driver (repo quick-check convention)

**Files:**
- Create: `skein-bootstrap/drivers/BootstrapDriver.java` (not part of the Gradle source set; run manually)

**Interfaces:**
- Consumes: `SkeinServerSession`, `SkeinClientSession`, `LocalPackServer`-style setup — but self-contained.

- [ ] **Step 1: Write the driver**

Create `skein-bootstrap/drivers/BootstrapDriver.java` with a `main` that: starts a `SkeinHttpServer` over a temp pack (mirroring `LocalPackServer`), builds a minimal inline `ClientPlatform` (tmp dirs, inline executor, no-op channel), runs `new SkeinClientSession(platform, e -> System.out.println(e)).run(grant)` with a grant whose `rootManifestHash` matches the server, and asserts the mod file appears under `gameDir/mods` and the outcome `isSuccess()`. Print `DRIVER OK` at the end. (Reuse the exact construction shown in Task A4 Step 2; keep it dependency-free.)

- [ ] **Step 2: Compile and run per the repo convention**

Compile order (from repo root), putting each module's `build/classes/java/main` (or freshly compiled classes) on `-cp`: common → core → platform, transfer, storage → server, app → bootstrap → the driver. Then run the driver's `main`.
Expected output ends with: `DRIVER OK`.

- [ ] **Step 3: Commit**
```bash
git add skein-bootstrap/drivers
git commit -m "test(bootstrap): standalone javac driver for the full session"
```

---

## Part B — `skein-neoforge` (written here, compiled/run on the build machine)

> Every source file in this part starts with:
> `// UNVERIFIED: needs NeoGradle toolchain — compile on build machine`
> These are NOT compiled in the dev environment. The `ControlChannel` /
> `ClientPlatform` seam is the stable contract; if a NeoForge 47.x API name differs
> from what is written, only the flagged registration/impl lines change — the shared
> logic in Part A does not.

### Task B1: Enable the module and its build script

**Files:**
- Modify: `settings.gradle.kts` (uncomment `include("skein-neoforge")`)
- Modify: `skein-neoforge/build.gradle.kts` (replace the commented template with a real script)

**Interfaces:**
- Produces: a `:skein-neoforge` project that resolves `:skein-bootstrap` and NeoForge.

- [ ] **Step 1: Uncomment the include**

In `settings.gradle.kts` replace `// include("skein-neoforge")` with `include("skein-neoforge")`. Leave `skein-forge` commented (later port).

- [ ] **Step 2: Write the real build script**

Replace `skein-neoforge/build.gradle.kts` contents with:
```kotlin
// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
plugins {
    id("net.neoforged.gradle.userdev") version providers.gradleProperty("neogradleVersion").get()
}

val minecraftVersion: String by project
val neoforgeVersion: String by project

dependencies {
    implementation("net.neoforged:neoforge:$neoforgeVersion")

    // skein-bootstrap deps are `implementation` (not `api`), so declare each module
    // whose types this adapter names directly. bootstrap gives ClientBootstrap/
    // ServerBootstrap/SkeinServerSession/SyncComponents; the others below are named
    // directly by SkeinMod / the platform impls.
    implementation(project(":skein-bootstrap"))
    implementation(project(":skein-platform"))
    implementation(project(":skein-core"))
    implementation(project(":skein-server"))
    implementation(project(":skein-common"))
}

// NeoGradle runs (client/server) — declare per the NeoGradle 7 DSL on the build machine.
```

- [ ] **Step 3: Verify on the build machine only**

Run (build machine): `./gradlew :skein-neoforge:dependencies`
Expected: resolves `:skein-bootstrap` and `net.neoforged:neoforge:47.1.106`. (Cannot run in the dev environment — do not attempt.)

- [ ] **Step 4: Commit**
```bash
git add settings.gradle.kts skein-neoforge/build.gradle.kts
git commit -m "build(neoforge): enable module with skein-bootstrap dependency"
```

---

### Task B2: Platform interface impls

**Files:**
- Create: `skein-neoforge/src/main/java/com/pathongko/skein/neoforge/platform/NeoForgePlatformPaths.java`
- Create: `.../neoforge/platform/NeoForgeGameThread.java`
- Create: `.../neoforge/platform/NeoForgeLoaderInfo.java`
- Create: `.../neoforge/platform/NeoForgeRelauncher.java`
- Create: `.../neoforge/platform/NeoForgeClientPlatform.java`

**Interfaces:**
- Produces: a `ClientPlatform` implementation (`NeoForgeClientPlatform`) aggregating the four impls plus a `ControlChannel` (Task B3).

- [ ] **Step 1: Implement `NeoForgePlatformPaths`**

All files begin with the UNVERIFIED banner. Map NeoForge paths:
```java
// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.platform;

import com.pathongko.skein.platform.PlatformPaths;
import java.nio.file.Path;

/** Resolves game directories from FML. On the build machine, confirm the FMLPaths
 *  accessors (gameDir(), configDir()) against NeoForge 47.x. */
public final class NeoForgePlatformPaths implements PlatformPaths {
    private final Path gameDir;
    public NeoForgePlatformPaths(Path gameDir) { this.gameDir = gameDir; }

    @Override public Path gameDir() { return gameDir; }
    @Override public Path configDir() { return gameDir.resolve("config"); }
    @Override public Path modsDir() { return gameDir.resolve("mods"); }
    @Override public Path skeinDir() { return gameDir.resolve(".skein"); }
}
```
Construct it from `FMLPaths.GAMEDIR.get()` at the call site (Task B5), so this class stays loader-API-free and could even be unit-tested.

- [ ] **Step 2: Implement `NeoForgeGameThread`**
```java
// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.platform;

import com.pathongko.skein.platform.GameThreadExecutor;
import net.minecraft.client.Minecraft;

/** Posts work onto the client main thread. */
public final class NeoForgeGameThread implements GameThreadExecutor {
    @Override public void execute(Runnable command) {
        Minecraft.getInstance().execute(command);
    }
}
```

- [ ] **Step 3: Implement `NeoForgeLoaderInfo`**
```java
// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.platform;

import com.pathongko.skein.platform.InstalledMod;
import com.pathongko.skein.platform.LoaderInfo;
import com.pathongko.skein.platform.LoaderType;
import java.util.List;

/** Reports loader facts. Populate installedMods/versions from ModList on the build
 *  machine (net.neoforged.fml.ModList.get()). */
public final class NeoForgeLoaderInfo implements LoaderInfo {
    private final String mcVersion;
    private final String skeinVersion;
    private final List<InstalledMod> mods;

    public NeoForgeLoaderInfo(String mcVersion, String skeinVersion, List<InstalledMod> mods) {
        this.mcVersion = mcVersion; this.skeinVersion = skeinVersion; this.mods = List.copyOf(mods);
    }
    @Override public LoaderType loader() { return LoaderType.NEOFORGE; }
    @Override public String minecraftVersion() { return mcVersion; }
    @Override public String skeinVersion() { return skeinVersion; }
    @Override public List<InstalledMod> installedMods() { return mods; }
}
```

- [ ] **Step 4: Implement `NeoForgeRelauncher`** (v1: manual restart)
```java
// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.platform;

import com.pathongko.skein.platform.ClientRelauncher;

/** v1 cannot relaunch the JVM; the UI asks the player to restart. */
public final class NeoForgeRelauncher implements ClientRelauncher {
    @Override public boolean canRelaunch() { return false; }
    @Override public void relaunch() { throw new UnsupportedOperationException("manual restart in v1"); }
}
```

- [ ] **Step 5: Implement `NeoForgeClientPlatform`** aggregating the above + the channel from B3
```java
// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.platform;

import com.pathongko.skein.platform.ClientPlatform;
import com.pathongko.skein.platform.ClientRelauncher;
import com.pathongko.skein.platform.GameThreadExecutor;
import com.pathongko.skein.platform.LoaderInfo;
import com.pathongko.skein.platform.PlatformPaths;
import com.pathongko.skein.platform.net.ControlChannel;

public final class NeoForgeClientPlatform implements ClientPlatform {
    private final LoaderInfo loaderInfo;
    private final PlatformPaths paths;
    private final GameThreadExecutor gameThread;
    private final ControlChannel controlChannel;
    private final ClientRelauncher relauncher;

    public NeoForgeClientPlatform(LoaderInfo loaderInfo, PlatformPaths paths,
            GameThreadExecutor gameThread, ControlChannel controlChannel, ClientRelauncher relauncher) {
        this.loaderInfo = loaderInfo; this.paths = paths; this.gameThread = gameThread;
        this.controlChannel = controlChannel; this.relauncher = relauncher;
    }
    @Override public LoaderInfo loaderInfo() { return loaderInfo; }
    @Override public PlatformPaths paths() { return paths; }
    @Override public GameThreadExecutor gameThread() { return gameThread; }
    @Override public ControlChannel controlChannel() { return controlChannel; }
    @Override public ClientRelauncher relauncher() { return relauncher; }
}
```

- [ ] **Step 6: Verify on build machine**

Run (build machine): `./gradlew :skein-neoforge:compileJava`
Expected: compiles. Fix any FML/Minecraft accessor names flagged in comments. (Do not attempt in dev environment.)

- [ ] **Step 7: Commit**
```bash
git add skein-neoforge/src/main/java/com/pathongko/skein/neoforge/platform
git commit -m "feat(neoforge): platform interface implementations"
```

---

### Task B3: `NeoForgeControlChannel` — networking over NeoForge

**Files:**
- Create: `skein-neoforge/src/main/java/com/pathongko/skein/neoforge/net/NeoForgeControlChannel.java`
- Create: `.../neoforge/net/SkeinNetwork.java` (channel registration)

**Interfaces:**
- Produces: a `ControlChannel` impl whose `sendToServer`/`sendToPlayer` carry a raw `byte[]`, and whose registration delivers inbound `byte[]` to the shared handler on the correct thread.

- [ ] **Step 1: Implement the channel registration + impl**

The shared code only needs one logical channel carrying an opaque `byte[]`. On NeoForge 47.x (1.20.1) this is done with a `SimpleChannel` (Forge-compatible networking retained in NeoForge). Write it isolated so only this file changes if the API name differs:
```java
// UNVERIFIED: needs NeoGradle toolchain — compile on build machine
package com.pathongko.skein.neoforge.net;

import com.pathongko.skein.platform.net.ControlChannel;
import com.pathongko.skein.platform.net.PlayerRef;
// NeoForge 47.x networking imports (confirm on build machine):
//   net.neoforged.neoforge.network.NetworkRegistry / simple.SimpleChannel
//   net.minecraft.server.level.ServerPlayer, net.minecraft.resources.ResourceLocation

/**
 * ControlChannel over one SimpleChannel. A single message type carries a byte[]
 * payload (an opcode is already baked into each payload by the shared codecs, so
 * this layer stays type-agnostic). The registration hops inbound packets to the
 * main thread before invoking the shared handler.
 */
public final class NeoForgeControlChannel implements ControlChannel {

    private volatile InboundHandler handler = (sender, payload) -> {};

    // The SimpleChannel instance + a small SkeinPayload(byte[]) message registered
    // in SkeinNetwork.register(). See SkeinNetwork below.

    @Override public void sendToServer(byte[] payload) {
        SkeinNetwork.CHANNEL_sendToServer(payload); // wraps payload in SkeinPayload and sends
    }

    @Override public void sendToPlayer(PlayerRef player, byte[] payload) {
        SkeinNetwork.CHANNEL_sendToPlayer(player, payload);
    }

    @Override public void onReceive(InboundHandler handler) {
        this.handler = handler;
    }

    /** Called by SkeinNetwork's registered receiver, already on the main thread. */
    void deliver(PlayerRef sender, byte[] payload) {
        handler.handle(sender, payload);
    }
}
```
And `SkeinNetwork.java` performs the actual `SimpleChannel` creation, registers a single `SkeinPayload` message (encode = write byte[]; decode = read byte[]), and on receive calls `enqueueWork(() -> channel.deliver(sender, bytes))`. Wrap the server `ServerPlayer` in a `PlayerRef` whose `id()` is the player UUID string; on the client, `sender` is `null`. Keep every Minecraft type inside `SkeinNetwork` and this file only.

- [ ] **Step 2: Verify on build machine**

Run (build machine): `./gradlew :skein-neoforge:compileJava`
Expected: compiles after the SimpleChannel API names are confirmed. This is the single most likely place to need adjustment — the `ControlChannel` contract does not change, only the registration calls.

- [ ] **Step 3: Commit**
```bash
git add skein-neoforge/src/main/java/com/pathongko/skein/neoforge/net
git commit -m "feat(neoforge): ControlChannel over NeoForge SimpleChannel"
```

---

### Task B4: `SkeinProgressScreen` — client GUI

**Files:**
- Create: `skein-neoforge/src/main/java/com/pathongko/skein/neoforge/client/SkeinProgressScreen.java`

**Interfaces:**
- Consumes: `SyncProgressListener` (implements it) + `SyncEvent` cases.
- Produces: a `Screen` the client shows during sync; updates a progress bar from `ProgressUpdated`, shows the terminal state from `SyncFinished`, and on `RestartRequired` shows a "please restart" panel.

- [ ] **Step 1: Implement the screen**

A `net.minecraft.client.gui.screens.Screen` that implements `SyncProgressListener`. Store the latest `SyncEvent`-derived state in volatile fields; `onEvent` (called off-thread) just updates fields — the render loop reads them. Handle:
- `DiffComputed` → total files/bytes label.
- `ProgressUpdated` → percentage bar (`bytesDone*100/bytesTotal`, guard divide-by-zero).
- `SyncFinished(outcome)` → if `RestartRequired`, show "Downloaded updates — please restart your game" + a Quit button (`Minecraft.getInstance().stop()`); if `Failed`, show the `ErrorCode` + detail + a Back button; if `UpToDate`/`Applied`, close the screen and continue.
Keep the class UNVERIFIED-banner headed. No logic beyond display — all decisions came from the shared session.

- [ ] **Step 2: Verify on build machine**

Run (build machine): `./gradlew :skein-neoforge:compileJava`
Expected: compiles. Confirm `Screen` render signature for 1.20.1 (`render(GuiGraphics, int, int, float)`).

- [ ] **Step 3: Commit**
```bash
git add skein-neoforge/src/main/java/com/pathongko/skein/neoforge/client
git commit -m "feat(neoforge): progress screen bound to SyncProgressListener"
```

---

### Task B5: `SkeinMod` entry point + server auto-start

**Files:**
- Create: `skein-neoforge/src/main/java/com/pathongko/skein/neoforge/SkeinMod.java`
- Create: `.../neoforge/server/SkeinServerConfig.java` (reads pack config from `configDir()`)
- Modify: `skein-neoforge/src/main/resources/META-INF/mods.toml` (already correct — verify only)

**Interfaces:**
- Consumes: everything from B2–B4 + `ClientBootstrap`, `ServerBootstrap`, `SkeinServerSession`, `SyncComponents.CATEGORY_DIRS`.
- Produces: the running mod. Client: builds `NeoForgeClientPlatform`, `ClientBootstrap.install()`, sends handshake reply and opens `SkeinProgressScreen` on the login/join event. Server: reads config, starts `SkeinHttpServer` + `ServerBootstrap.install()`.

- [ ] **Step 1: Implement the server config reader**

`SkeinServerConfig` reads a small properties/JSON file `skein-server.properties` under `configDir()`: `packDir`, `httpPort`, `clientToken` (optional), `adminPassword` (optional), `publicEndpoint` (the URL clients use, e.g. `http://your-host:25585`). Provide sane defaults and write a template file if absent. Use the existing dependency-free `Json` reader in `skein-common` if JSON is preferred; otherwise `java.util.Properties`. UNVERIFIED banner not required here (no Minecraft import) — but this file may also be unit-tested in `skein-bootstrap` if moved there later (out of scope now).

- [ ] **Step 2: Implement `SkeinMod`**

`@Mod("skein")` class. In the constructor, register lifecycle listeners:
- Common: create the `NeoForgeControlChannel`, call `SkeinNetwork.register(channel)` in the common setup event.
- Dedicated server (`FMLDedicatedServerSetupEvent` / server-starting event): read `SkeinServerConfig`, build a `ManagedPack`/`Supplier<GeneratedPack>` over the configured pack dir (reuse the construction from `SkeinCli.server`), start `SkeinHttpServer` (+ optional `AdminSurface`), and `new ServerBootstrap(channel, new SkeinServerSession(publicEndpoint, token, pack::current, new MessageDigestHashService())).install()`.
- Client (`FMLClientSetupEvent` + a client login/join event): build `NeoForgePlatformPaths(FMLPaths.GAMEDIR.get())`, `NeoForgeGameThread`, `NeoForgeLoaderInfo` (MC version + this mod's version from `ModList`), `NeoForgeRelauncher`, and `NeoForgeClientPlatform(...)`. Create a `SkeinProgressScreen`, a single-thread background `Executor` (`Executors.newSingleThreadExecutor`), and `new ClientBootstrap(platform, executor, screen).install()`. On join, `Minecraft.getInstance().setScreen(screen)` and `bootstrap.sendHandshakeReply()`.
All Minecraft-touching lines carry the UNVERIFIED banner (file-level).

- [ ] **Step 3: Verify mods.toml**

Confirm `skein-neoforge/src/main/resources/META-INF/mods.toml` matches modId `skein`, `loaderVersion="[47,)"`, MC `[1.20.1,1.21)`. It already does — no change expected.

- [ ] **Step 4: Verify on build machine**

Run (build machine): `./gradlew :skein-neoforge:build`
Expected: BUILD SUCCESSFUL. Then `./gradlew :skein-neoforge:runServer` with a demo pack configured, and `:runClient` joining it — confirm files land in `mods/`+`config/` and the restart prompt appears. (Dev environment cannot do this.)

- [ ] **Step 5: Commit**
```bash
git add skein-neoforge/src
git commit -m "feat(neoforge): mod entry point, client bootstrap wiring, server auto-start"
```

---

### Task B6: Build-machine verification checklist (documentation)

**Files:**
- Create: `skein-neoforge/BUILD_AND_VERIFY.md`

- [ ] **Step 1: Write the checklist**

Document, for the build machine: prerequisites (JDK 17, internet for mappings), `./gradlew :skein-neoforge:build`, how to configure `config/skein-server.properties`, `runServer`/`runClient` steps, expected result (mod jar in `mods/`, restart prompt), and the known adjustment points (SimpleChannel API in Task B3, FML path/ModList accessors in B2, `Screen.render` signature in B4). Note that any code edits needed to make it compile are expected and should be committed back.

- [ ] **Step 2: Commit**
```bash
git add skein-neoforge/BUILD_AND_VERIFY.md
git commit -m "docs(neoforge): build-and-verify checklist for the toolchain machine"
```

---

## Self-review notes (done during authoring)

- **Spec coverage:** `skein-bootstrap` (A1–A7) covers ClientSession/ServerSession/composition root/RootHashCache/tests; `skein-neoforge` (B1–B6) covers platform impls, ControlChannel, progress Screen, mod entry, server auto-start, mods.toml. The chosen UX (stage + manual restart) is in B4/B5; server auto-start is in B5. Out-of-scope items (per-session token, auto-relaunch, Fabric, single-player host) are not implemented — matches spec.
- **Unverifiable surface:** all `net.minecraft.*` code is confined to Part B, each file banner-marked; Part A is fully verified here.
- **Type consistency:** session returns the existing `SyncOutcome` (no new `SessionResult` type — YAGNI vs. spec's tentative name; noted). Channel/session/bootstrap signatures are consistent across A4–A6 and consumed unchanged in B5.
- **Open confirmations flagged inline (not placeholders):** `SkeinHttpServer` stop-method name, `FileHash.value()` accessor name, `skein-app`/other build-script shape — each task says to confirm against the real file and adjust.
