# Sync-on-join Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a client sync a modpack over HTTP after a mod-mismatch join failure — discover the Skein endpoint from the server address, fall back to manual entry — so mods download despite Forge rejecting the connection during the login handshake.

**Architecture:** All decision logic lands in the shared, unit-testable `skein-bootstrap` module: a pure endpoint-deriver, an HTTP probe, and a `SkeinClientSession` that can sync from a raw `(endpoint, token)` instead of only a play-phase `SessionGrantS2C`. The loader modules keep only a thin, `UNVERIFIED` GUI bridge that intercepts the disconnect screen and drives the shared code.

**Tech Stack:** Java 17, JUnit 5 + AssertJ, JDK `java.net.http` (no external deps), `com.sun.net.httpserver` for test servers. Forge/NeoForge client API in the loader task.

## Global Constraints

- `skein-core` must never import `net.minecraft.*`; shared modules depend only inward (common ← core ← platform/transfer/storage/app ← bootstrap). New shared code goes in `skein-bootstrap`.
- No new external runtime dependencies — JDK-only (`java.net.http`, `com.sun.net.httpserver`).
- Packets/serialization stay `byte[]`-based; do not introduce `FriendlyByteBuf` into shared code.
- Loader modules (`skein-forge`, `skein-neoforge`) cannot be built in this environment. Loader code is written but marked `// UNVERIFIED: needs toolchain — compile on build machine` and verified via Gradle on the toolchain machine, per existing repo convention.
- Default Skein HTTP port is `25585` (matches `SkeinServerConfig` default).
- Java 17 toolchain auto-detected at `C:\Program Files\Java\jdk-17`; run tests with `./gradlew`.

---

### Task 1: `SkeinEndpoint` — derive a Skein base URL from a Minecraft server address

**Files:**
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinEndpoint.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinEndpointTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `SkeinEndpoint.DEFAULT_SKEIN_PORT` (int, 25585); `static String SkeinEndpoint.deriveFrom(String mcAddress)`; `static String SkeinEndpoint.deriveFrom(String mcAddress, int skeinPort)`. Returns a base URL like `http://<host>:<port>` (no trailing slash).

- [ ] **Step 1: Write the failing test**

```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SkeinEndpointTest {

    @Test
    void hostAndPortDropsThePortAndUsesTheSkeinPort() {
        assertThat(SkeinEndpoint.deriveFrom("1.2.3.4:25565"))
                .isEqualTo("http://1.2.3.4:25585");
    }

    @Test
    void hostOnlyUsesTheSkeinPort() {
        assertThat(SkeinEndpoint.deriveFrom("play.example.com"))
                .isEqualTo("http://play.example.com:25585");
    }

    @Test
    void customSkeinPortIsHonoured() {
        assertThat(SkeinEndpoint.deriveFrom("play.example.com:25565", 9000))
                .isEqualTo("http://play.example.com:9000");
    }

    @Test
    void bracketedIpv6WithPortKeepsBrackets() {
        assertThat(SkeinEndpoint.deriveFrom("[fe80::1]:25565"))
                .isEqualTo("http://[fe80::1]:25585");
    }

    @Test
    void bareIpv6GetsBracketed() {
        assertThat(SkeinEndpoint.deriveFrom("::1"))
                .isEqualTo("http://[::1]:25585");
    }

    @Test
    void blankAddressIsRejected() {
        assertThatThrownBy(() -> SkeinEndpoint.deriveFrom("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SkeinEndpointTest"`
Expected: FAIL — `SkeinEndpoint` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.pathongko.skein.bootstrap;

/**
 * Derives a Skein HTTP base URL from the Minecraft server address the player
 * tried to join. Convention: same host, Skein's HTTP port (default 25585).
 * Used by the disconnect-driven sync flow, which has no play-phase channel to
 * receive the endpoint from.
 */
public final class SkeinEndpoint {

    public static final int DEFAULT_SKEIN_PORT = 25585;

    private SkeinEndpoint() {
    }

    public static String deriveFrom(String mcAddress) {
        return deriveFrom(mcAddress, DEFAULT_SKEIN_PORT);
    }

    public static String deriveFrom(String mcAddress, int skeinPort) {
        return "http://" + extractHost(mcAddress) + ":" + skeinPort;
    }

    /** Host portion, bracketed for use in a URL when it is an IPv6 literal. */
    private static String extractHost(String address) {
        if (address == null || address.trim().isEmpty()) {
            throw new IllegalArgumentException("blank server address");
        }
        String s = address.trim();
        if (s.startsWith("[")) { // bracketed IPv6, optionally followed by :port
            int close = s.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("malformed IPv6 address: " + address);
            }
            return s.substring(0, close + 1);
        }
        int firstColon = s.indexOf(':');
        if (firstColon < 0) {
            return s; // host only
        }
        if (firstColon != s.lastIndexOf(':')) {
            return "[" + s + "]"; // multiple colons -> bare IPv6 literal, no port
        }
        return s.substring(0, firstColon); // host:port
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SkeinEndpointTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinEndpoint.java \
        skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinEndpointTest.java
git commit -m "feat(sync): derive Skein HTTP endpoint from a server address

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `EndpointProbe` — is there a Skein server at this URL?

**Files:**
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/EndpointProbe.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/EndpointProbeTest.java`

**Interfaces:**
- Consumes: `com.pathongko.skein.transfer.http.HttpRangeClient` / `JdkHttpRangeClient` / `RangeResponse`; `com.pathongko.skein.core.manifest.JsonManifestCodec`.
- Produces: `new EndpointProbe(java.time.Duration timeout)`; `boolean probe(String baseUrl, java.util.Optional<String> token)` — true iff `GET <baseUrl>/manifest/root` returns HTTP 200 and the body decodes as a root manifest. Never throws.

- [ ] **Step 1: Write the failing test**

```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.bootstrap.support.LocalPackServer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EndpointProbeTest {

    private final EndpointProbe probe = new EndpointProbe(Duration.ofSeconds(5));

    @Test
    void findsARealSkeinServer(@TempDir Path root) throws Exception {
        try (LocalPackServer server = new LocalPackServer(root.resolve("pack"))) {
            assertThat(probe.probe(server.endpoint(), Optional.empty())).isTrue();
        }
    }

    @Test
    void rejectsAnUnreachableHost() {
        assertThat(probe.probe("http://127.0.0.1:1", Optional.empty())).isFalse();
    }

    @Test
    void rejectsANonSkeinServerReturningNonManifestBody(@TempDir Path root) throws Exception {
        try (PlainHttp server = new PlainHttp(200, "not a manifest")) {
            assertThat(probe.probe(server.baseUrl(), Optional.empty())).isFalse();
        }
    }

    @Test
    void rejectsA404(@TempDir Path root) throws Exception {
        try (PlainHttp server = new PlainHttp(404, "nope")) {
            assertThat(probe.probe(server.baseUrl(), Optional.empty())).isFalse();
        }
    }

    /** Minimal fixed-response HTTP server for the negative cases. */
    private static final class PlainHttp implements AutoCloseable {
        private final com.sun.net.httpserver.HttpServer server;
        PlainHttp(int status, String body) throws Exception {
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                try (var os = exchange.getResponseBody()) { os.write(bytes); }
            });
            server.start();
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() { server.stop(0); }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.EndpointProbeTest"`
Expected: FAIL — `EndpointProbe` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.transfer.http.HttpRangeClient;
import com.pathongko.skein.transfer.http.JdkHttpRangeClient;
import com.pathongko.skein.transfer.http.RangeResponse;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * Cheap check that a Skein HTTP server lives at a base URL: fetch
 * {@code /manifest/root} and confirm it decodes as a root manifest. Used to
 * validate an auto-derived endpoint before syncing, and to validate a URL the
 * player typed manually. Never throws — a probe failure is just {@code false}.
 */
public final class EndpointProbe {

    private final Duration timeout;
    private final JsonManifestCodec codec = new JsonManifestCodec();

    public EndpointProbe(Duration timeout) {
        this.timeout = Preconditions.requireNonNull(timeout, "timeout");
    }

    public boolean probe(String baseUrl, Optional<String> token) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        String url = trimTrailingSlash(baseUrl) + "/manifest/root";
        HttpRangeClient client = new JdkHttpRangeClient(
                HttpClient.newHttpClient(), timeout, () -> token);
        try (RangeResponse response = client.open(URI.create(url), 0)) {
            if (response.statusCode() != 200) {
                return false;
            }
            codec.decodeRoot(response.body().readAllBytes());
            return true;
        } catch (RuntimeException | java.io.IOException e) {
            return false;
        }
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.EndpointProbeTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/EndpointProbe.java \
        skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/EndpointProbeTest.java
git commit -m "feat(sync): probe a URL for a live Skein server

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `SkeinClientSession` — sync from a raw `(endpoint, token)`

**Files:**
- Modify: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinClientSession.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinClientSessionTest.java` (add a test)

**Interfaces:**
- Consumes: `SkeinComponents.forSync`, `RootHashCache`, `ClientPlatform.paths()` (`skeinDir()`, `gameDir()`), `SyncEvent`, `SyncOutcome`, `EventBus`/`SynchronousEventBus`.
- Produces: new public `SyncOutcome run(String transferEndpoint, java.util.Optional<String> token, java.util.Optional<String> expectedRootHash)`. Existing `SyncOutcome run(SessionGrantS2C grant)` is retained and now delegates to it. Never throws.

- [ ] **Step 1: Write the failing test** (append to `SkeinClientSessionTest`)

```java
    @Test
    void syncsFromAnEndpointWithoutAGrant(@TempDir Path root) throws Exception {
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");
        try (LocalPackServer server = new LocalPackServer(packDir)) {
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);
            SkeinClientSession session = new SkeinClientSession(platform, e -> {});

            SyncOutcome outcome = session.run(
                    server.endpoint(), java.util.Optional.empty(), java.util.Optional.empty());

            assertThat(outcome).isInstanceOf(SyncOutcome.RestartRequired.class);
            assertThat(Files.exists(gameDir.resolve("mods").resolve("example.jar"))).isTrue();
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SkeinClientSessionTest"`
Expected: FAIL — `run(String, Optional, Optional)` is not defined (compilation error).

- [ ] **Step 3: Refactor the implementation**

Replace the body of `SkeinClientSession` (keep the package, imports, fields, and constructor) so the two `run` methods read exactly:

```java
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
        RootHashCache cache = new RootHashCache(paths.skeinDir());

        if (expectedRootHash.isPresent()
                && cache.read().filter(h -> h.equals(expectedRootHash.get())).isPresent()) {
            SyncOutcome upToDate = new SyncOutcome.UpToDate();
            try {
                listener.onEvent(new SyncEvent.SyncFinished(upToDate));
            } catch (RuntimeException ignored) {
                // listener is display code; its failure must not break the never-throws contract
            }
            return upToDate;
        }

        EventBus events = new SynchronousEventBus();
        events.subscribe(listener::onEvent);

        SyncOrchestrator orchestrator = SyncComponents.forSync(
                transferEndpoint, paths.gameDir(), token, events);

        SyncOutcome outcome = orchestrator.run();
        if (outcome.isSuccess() && expectedRootHash.isPresent()) {
            try {
                cache.write(expectedRootHash.get());
            } catch (IllegalArgumentException ignored) {
                // best-effort cache; a blank hash just costs a redundant diff next time
            }
        }
        return outcome;
    }
```

(No import changes are needed — `Optional`, `Preconditions`, `PlatformPaths`, `EventBus`, `SynchronousEventBus`, `SyncEvent`, `SyncOutcome`, `SyncOrchestrator`, and `SessionGrantS2C` are all already imported.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SkeinClientSessionTest"`
Expected: PASS (4 tests — the 3 existing grant-based tests still pass because `run(grant)` delegates unchanged).

- [ ] **Step 5: Commit**

```bash
git add skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinClientSession.java \
        skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinClientSessionTest.java
git commit -m "feat(sync): sync a client session from a raw endpoint, not only a grant

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: `SkeinJoinSync` orchestration helper (shared) + loader disconnect hook

This task has a **shared, testable half** (the discover→probe→sync orchestration, no
Minecraft) and a **loader half** (the DisconnectedScreen bridge) that is `UNVERIFIED`
and compiled only on the toolchain machine.

**Files:**
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinJoinSync.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinJoinSyncTest.java`
- Create: `skein-forge/src/main/java/com/pathongko/skein/forge/client/SkeinDisconnectHook.java`
- Create: `skein-neoforge/src/main/java/com/pathongko/skein/neoforge/client/SkeinDisconnectHook.java`

**Interfaces:**
- Consumes: `SkeinEndpoint.deriveFrom`, `EndpointProbe.probe`, `SkeinClientSession.run(String, Optional, Optional)`, `ClientPlatform`, `SyncProgressListener`.
- Produces (shared):
  - `new SkeinJoinSync(ClientPlatform platform, EndpointProbe probe)`
  - `Optional<String> discover(String mcAddress, Optional<String> token)` — returns the derived base URL if a Skein server answers there, else empty.
  - `SyncOutcome syncFrom(String baseUrl, Optional<String> token, SyncProgressListener listener)` — runs a session against an explicit URL (used by both the auto path and the manual-entry path).

#### Shared half (test-driven, runs here)

- [ ] **Step 1: Write the failing test**

```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.bootstrap.support.LocalPackServer;
import com.pathongko.skein.core.sync.SyncOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkeinJoinSyncTest {

    @Test
    void discoverFindsAServerOnTheConventionalPort(@TempDir Path root) throws Exception {
        try (LocalPackServer server = new LocalPackServer(root.resolve("pack"))) {
            FakeClientPlatform platform = new FakeClientPlatform(root.resolve("client"));
            SkeinJoinSync join = new SkeinJoinSync(platform, new EndpointProbe(Duration.ofSeconds(5)));

            // Probe the ephemeral URL directly (discoverAt), so the test doesn't
            // depend on the conventional port 25585 being free on the CI box.
            Optional<String> found = join.discoverAt(server.endpoint(), Optional.empty());
            assertThat(found).contains(server.endpoint());
        }
    }

    @Test
    void discoverReturnsEmptyWhenNothingAnswers(@TempDir Path root) {
        FakeClientPlatform platform = new FakeClientPlatform(root.resolve("client"));
        SkeinJoinSync join = new SkeinJoinSync(platform, new EndpointProbe(Duration.ofSeconds(2)));
        assertThat(join.discoverAt("http://127.0.0.1:1", Optional.empty())).isEmpty();
    }

    @Test
    void syncFromDownloadsThePack(@TempDir Path root) throws Exception {
        try (LocalPackServer server = new LocalPackServer(root.resolve("pack"))) {
            Path gameDir = root.resolve("client");
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);
            SkeinJoinSync join = new SkeinJoinSync(platform, new EndpointProbe(Duration.ofSeconds(5)));

            SyncOutcome outcome = join.syncFrom(server.endpoint(), Optional.empty(), e -> {});

            assertThat(outcome).isInstanceOf(SyncOutcome.RestartRequired.class);
            assertThat(Files.exists(gameDir.resolve("mods").resolve("example.jar"))).isTrue();
        }
    }
}
```

Note: the test uses a helper `discoverAt(baseUrl, token)` that probes an already-formed
URL, so the test does not depend on port 25585 being free. `discover(mcAddress, token)`
(the address-deriving entry point the loader calls) is a one-liner over `discoverAt`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SkeinJoinSyncTest"`
Expected: FAIL — `SkeinJoinSync` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.platform.ClientPlatform;

import java.util.Optional;

/**
 * Orchestrates the disconnect-driven sync, with no Minecraft dependency so it is
 * fully unit-testable: derive the conventional Skein URL from the address the
 * player tried, probe it, and — if it answers — sync from it. The loader hook is
 * a thin caller of this class.
 */
public final class SkeinJoinSync {

    private final ClientPlatform platform;
    private final EndpointProbe probe;

    public SkeinJoinSync(ClientPlatform platform, EndpointProbe probe) {
        this.platform = Preconditions.requireNonNull(platform, "platform");
        this.probe = Preconditions.requireNonNull(probe, "probe");
    }

    /** Derive the Skein URL from the joined address and probe it. */
    public Optional<String> discover(String mcAddress, Optional<String> token) {
        return discoverAt(SkeinEndpoint.deriveFrom(mcAddress), token);
    }

    /** Probe an already-formed base URL (used by tests and manual entry validation). */
    public Optional<String> discoverAt(String baseUrl, Optional<String> token) {
        return probe.probe(baseUrl, token) ? Optional.of(baseUrl) : Optional.empty();
    }

    /** Run a sync session against an explicit base URL. Never throws. */
    public SyncOutcome syncFrom(String baseUrl, Optional<String> token,
                                SyncProgressListener listener) {
        return new SkeinClientSession(platform, listener)
                .run(baseUrl, token, Optional.empty());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :skein-bootstrap:test --tests "com.pathongko.skein.bootstrap.SkeinJoinSyncTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit the shared half**

```bash
git add skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinJoinSync.java \
        skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/SkeinJoinSyncTest.java
git commit -m "feat(sync): discover-probe-sync orchestration for the join flow

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

#### Loader half (UNVERIFIED — compiled and run only on the toolchain machine)

- [ ] **Step 6: Write the Forge disconnect hook**

Create `skein-forge/src/main/java/com/pathongko/skein/forge/client/SkeinDisconnectHook.java`:

```java
// UNVERIFIED: needs ForgeGradle toolchain — compile on build machine.
// Client-only (references net.minecraft.client.*). Reached only via SkeinClient's
// Dist.CLIENT-guarded init, never from server/common code.
package com.pathongko.skein.forge.client;

import com.pathongko.skein.bootstrap.EndpointProbe;
import com.pathongko.skein.bootstrap.SkeinJoinSync;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.platform.ClientPlatform;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Intercepts Minecraft's disconnect screen after a mod-mismatch join failure and
 * drives {@link SkeinJoinSync}: derive the Skein URL from the address just tried,
 * probe it, and sync. All decisions live in shared, tested code; this class only
 * bridges Forge client events to it.
 *
 * <p>confirm on build machine:
 * <ul>
 *   <li>ScreenEvent.Init.Post is the correct post-init screen event on Forge 47.x.</li>
 *   <li>How to read the address the player tried: capture it when the connection
 *       starts (e.g. ConnectScreen/ServerData) into {@code lastAttempted}, since
 *       Minecraft#getCurrentServer() may be null on the disconnect screen.</li>
 *   <li>DisconnectedScreen exposes the reason Component to distinguish a
 *       mod-mismatch disconnect from an ordinary kick.</li>
 * </ul>
 */
public final class SkeinDisconnectHook {

    private final ClientPlatform platform;
    private final Executor syncExecutor;
    private final SkeinProgressScreen screen;

    // Set when a join is initiated; read on the disconnect screen.
    private volatile String lastAttempted;

    public SkeinDisconnectHook(ClientPlatform platform, Executor syncExecutor,
                               SkeinProgressScreen screen) {
        this.platform = platform;
        this.syncExecutor = syncExecutor;
        this.screen = screen;
    }

    public void install() {
        MinecraftForge.EVENT_BUS.addListener(this::onScreenInit);
    }

    /** Record the address a join is targeting (wire from the connect path). */
    public void rememberAttempt(String mcAddress) {
        this.lastAttempted = mcAddress;
    }

    private void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof DisconnectedScreen)) {
            return;
        }
        String attempted = this.lastAttempted;
        if (attempted == null) {
            return;
        }
        // Offer sync: open the Skein screen and start discovery on the sync thread.
        Minecraft.getInstance().setScreen(screen);
        SkeinJoinSync join = new SkeinJoinSync(platform, new EndpointProbe(Duration.ofSeconds(10)));
        syncExecutor.execute(() -> {
            Optional<String> url = join.discover(attempted, Optional.empty());
            if (url.isPresent()) {
                SyncOutcome outcome = join.syncFrom(url.get(), Optional.empty(), screen);
                // screen already reflects SyncEvents via the listener; nothing else to do.
            } else {
                screen.promptForManualUrl(attempted); // shows the URL field
            }
        });
    }
}
```

- [ ] **Step 7: Add the manual-URL field to `SkeinProgressScreen` (both loaders)**

In `skein-forge/.../client/SkeinProgressScreen.java` and the NeoForge mirror, add (marked
`// UNVERIFIED`):

```java
    /**
     * Shown when auto-discovery failed: render an EditBox for the player to type the
     * Skein URL and a Sync button that re-runs syncFrom(...) with it. confirm on build
     * machine: EditBox constructor + Button.builder signatures for this MC version.
     */
    public void promptForManualUrl(String attemptedAddress) {
        // Set a volatile flag read by render()/init() to add:
        //   - net.minecraft.client.gui.components.EditBox for the URL
        //     (prefilled with SkeinEndpoint.deriveFrom(attemptedAddress))
        //   - a "Sync" Button whose onPress reads the EditBox text and calls
        //     the injected SkeinJoinSync.syncFrom(text, Optional.empty(), this)
        //     on the sync executor.
        this.manualUrlRequested = true;
        this.manualUrlPrefill = attemptedAddress;
    }
```

(Wire the volatile fields and the widget construction in `maybeBuildTerminalUi` /
`init`, following the existing volatile-field + game-thread-widget pattern already in
this screen.)

- [ ] **Step 8: Register the hook on the client (both loaders)**

In `skein-forge/.../client/SkeinClient.java` `onClientSetup(...)`, after the existing
`ClientBootstrap` wiring, add:

```java
            SkeinDisconnectHook disconnectHook =
                    new SkeinDisconnectHook(platform, syncExecutor, screen);
            disconnectHook.install();
            // confirm on build machine: also call disconnectHook.rememberAttempt(addr)
            // from wherever a server join is initiated (multiplayer connect path).
```

Mirror all three edits in `skein-neoforge` (only the loader-API package prefixes differ:
`net.neoforged.neoforge.client.event.ScreenEvent`, `net.neoforged.neoforge.common.NeoForge`).

- [ ] **Step 9: Verify on the toolchain machine (manual — cannot run here)**

Run on the build machine:
```
./gradlew :skein-forge:build :skein-neoforge:build
```
Expected: both compile.

Then `./gradlew :skein-forge:runClient`, connect to a server missing a mod, and confirm:
1. The disconnect is intercepted and the Skein screen opens.
2. Auto-discovery finds the server (Skein HTTP on `<host>:25585`) and downloads the mod.
3. "please restart" shows; after restart + rejoin, the handshake passes.
4. With Skein HTTP stopped, the manual-URL field appears and a typed URL works.

- [ ] **Step 10: Commit the loader half**

```bash
git add skein-forge/src/main/java/com/pathongko/skein/forge/client/SkeinDisconnectHook.java \
        skein-neoforge/src/main/java/com/pathongko/skein/neoforge/client/SkeinDisconnectHook.java \
        skein-forge/src/main/java/com/pathongko/skein/forge/client/SkeinProgressScreen.java \
        skein-neoforge/src/main/java/com/pathongko/skein/neoforge/client/SkeinProgressScreen.java \
        skein-forge/src/main/java/com/pathongko/skein/forge/client/SkeinClient.java \
        skein-neoforge/src/main/java/com/pathongko/skein/neoforge/client/SkeinClient.java
git commit -m "feat(loader): intercept mod-mismatch disconnect and sync over HTTP

UNVERIFIED: loader-API code, verify via Gradle on the toolchain machine.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- User flow (derive → probe → sync → restart): Tasks 1, 2, 4 (shared) + Task 4 loader half. ✓
- HTTP-only sync independent of MC connection: Task 3. ✓
- Zero-config discovery + manual fallback: Task 4 (`discover` / `promptForManualUrl`). ✓
- Additive (no delete): already committed (`363ee1e`); no task needed. ✓
- Token in manual fallback: `probe`/`syncFrom` accept `Optional<String> token`; manual field passes it. ✓ (v1 auto path uses empty token, per spec.)
- Keep existing handshake code, stop depending on it: Tasks 3/4 add new paths; nothing deletes `ControlChannel`/`SkeinNetwork`. ✓
- Testing (SkeinEndpointTest, EndpointProbeTest, extended SkeinClientSessionTest, SkeinJoinSyncTest): Tasks 1–4. ✓
- Loader verified on toolchain: Task 4 Step 9. ✓

**Placeholder scan:** No TBD/TODO in shared tasks. The loader half's `// confirm on build machine` notes are the repo's established convention for un-compilable loader API, not placeholders — each names the specific API to confirm. ✓

**Type consistency:** `deriveFrom(String)`/`deriveFrom(String,int)`, `probe(String,Optional<String>)`, `run(String,Optional<String>,Optional<String>)`, `SkeinJoinSync.discover/discoverAt/syncFrom` are used identically across tasks. `SyncProgressListener` is the existing listener type accepted by `SkeinClientSession`'s constructor. ✓
