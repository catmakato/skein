# Whitelist-Gated Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a server operator restrict pack sync, per managed server, to players on that Minecraft server's whitelist — toggled live from the admin web dashboard, and durable across a dedicated-server restart.

**Architecture:** The server already learns the connecting player's UUID during the trusted, game-native handshake (`ControlChannel`'s `PlayerRef`), before any sync grant is handed out. Thread that identity into `SkeinServerSession.onHandshake`, check it against the vanilla `whitelist.json` when a per-server gate is on, and — if the player isn't listed — return a denied grant instead of a real one, so the client never learns the transfer endpoint/token. The gate's on/off state lives on `ServerNode`, mutable via a new admin-dashboard endpoint, and is persisted as a tiny marker file under the pack directory so it survives a JVM restart.

**Tech Stack:** Java 17, JUnit 5 + AssertJ (existing project test stack), `skein-platform`'s own `PacketCodec`/`PacketReader`/`PacketWriter` (no Minecraft types), `skein-common`'s dependency-free `Json` reader for `whitelist.json`.

**Spec:** `docs/superpowers/specs/2026-08-28-whitelist-gated-sync-design.md`

## Global Constraints

- `skein-core` must never import `net.minecraft.*` and must depend on nothing but `skein-common` (existing architecture rule — this feature touches `skein-core` only to add one `ErrorCode` value, no Minecraft types involved).
- Packets are serialized via `skein-platform`'s `PacketWriter`/`PacketReader`/`PacketCodec` — never Minecraft's `FriendlyByteBuf` — in shared code.
- No `new Thread()`/`Executors` in core; this feature adds none.
- Verify `skein-forge` changes by actually running `./gradlew :skein-forge:build` (confirmed green in this environment). `skein-neoforge` is commented out of `settings.gradle.kts` and unverified here — mirror the Forge edit by hand into the NeoForge file, but do not claim it compiles.
- Every module below already exists and is wired into `settings.gradle.kts`; no new Gradle modules are created by this plan.

---

## File Structure

| File | Responsibility |
|---|---|
| `skein-platform/src/main/java/com/pathongko/skein/platform/net/packet/SessionGrantS2C.java` | Modify: add `granted`/`deniedReason` fields + `granted(...)`/`denied(...)` factories |
| `skein-platform/src/test/java/com/pathongko/skein/platform/net/PacketCodecTest.java` | Modify: update round-trip test for new shape, add a denied-shape round-trip |
| `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/MinecraftWhitelist.java` | Create: reads vanilla `whitelist.json`, answers "is this UUID listed?" |
| `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/MinecraftWhitelistTest.java` | Create: tests for `MinecraftWhitelist` |
| `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinServerSession.java` | Modify: `onHandshake` takes the sender's `PlayerRef`, gates on whitelist |
| `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/ServerBootstrap.java` | Modify: thread `sender` into `onHandshake` |
| `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/ServerHandshakeTest.java` | Modify: update existing tests for new signature, add gate-on tests |
| `skein-server/src/main/java/com/pathongko/skein/server/manage/ServerNode.java` | Modify: mutable `whitelistGateEnabled`, marker-file persistence |
| `skein-server/src/test/java/com/pathongko/skein/server/manage/ServerNodeTest.java` | Create: tests for the toggle + marker-file durability |
| `skein-server/src/main/java/com/pathongko/skein/server/admin/AdminSurface.java` | Modify: new `whitelist-gate` endpoint, expose state in JSON views |
| `skein-server/src/test/java/com/pathongko/skein/server/AdminSurfaceIntegrationTest.java` | Modify: add a test for the new endpoint |
| `skein-server/src/main/resources/admin/index.html` | Modify: checkbox in the per-server panel |
| `skein-core/src/main/java/com/pathongko/skein/core/error/ErrorCode.java` | Modify: add `ACCESS_DENIED` |
| `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/ClientBootstrap.java` | Modify: branch on `grant.granted()`, report denial without starting a session |
| `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/ClientBootstrapTest.java` | Modify: update existing test for new packet shape, add a denial test |
| `skein-forge/src/main/java/com/pathongko/skein/forge/SkeinMod.java` | Modify: build a `MinecraftWhitelist`, pass it + the gate supplier into `SkeinServerSession` |
| `skein-neoforge/src/main/java/com/pathongko/skein/neoforge/SkeinMod.java` | Modify: same edit, mirrored (unverified build) |

---

### Task 1: Extend `SessionGrantS2C` with a granted/denied shape

**Files:**
- Modify: `skein-platform/src/main/java/com/pathongko/skein/platform/net/packet/SessionGrantS2C.java`
- Test: `skein-platform/src/test/java/com/pathongko/skein/platform/net/PacketCodecTest.java`

**Interfaces:**
- Produces: `SessionGrantS2C.granted(String transferEndpoint, String sessionToken, String packVersion, String rootManifestHash)`, `SessionGrantS2C.denied(String reason)`, and the record's `granted()`/`deniedReason()` accessors — used by Task 3 (server side) and Task 7 (client side).

- [ ] **Step 1: Write the failing test**

Replace `PacketCodecTest.java`'s `sessionGrantRoundTrips` test and add a denied-shape test:

```java
package com.pathongko.skein.platform.net;

import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The codec round-trip is the contract that lets Forge and NeoForge exchange the
 * same packets: encode -> bytes -> decode must reproduce the original exactly.
 */
class PacketCodecTest {

    @Test
    void handshakeReplyRoundTrips() {
        HandshakeReplyC2S original = new HandshakeReplyC2S(true, 100, "0.0.1-SNAPSHOT");
        byte[] wire = HandshakeReplyC2S.CODEC.toBytes(original);
        assertThat(HandshakeReplyC2S.CODEC.fromBytes(wire)).isEqualTo(original);
    }

    @Test
    void sessionGrantRoundTrips() {
        SessionGrantS2C original = SessionGrantS2C.granted(
                "http://127.0.0.1:25566/skein", "tok-abc123", "2026.07.05-3",
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2");
        byte[] wire = SessionGrantS2C.CODEC.toBytes(original);
        assertThat(SessionGrantS2C.CODEC.fromBytes(wire)).isEqualTo(original);
    }

    @Test
    void sessionDenialRoundTrips() {
        SessionGrantS2C original = SessionGrantS2C.denied("You are not on this server's whitelist.");
        byte[] wire = SessionGrantS2C.CODEC.toBytes(original);
        SessionGrantS2C decoded = SessionGrantS2C.CODEC.fromBytes(wire);
        assertThat(decoded).isEqualTo(original);
        assertThat(decoded.granted()).isFalse();
        assertThat(decoded.deniedReason()).isEqualTo("You are not on this server's whitelist.");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-platform:test --tests "*PacketCodecTest*"`
Expected: FAIL — `SessionGrantS2C.granted(...)` and `SessionGrantS2C.denied(...)` don't exist yet (compile error).

- [ ] **Step 3: Write minimal implementation**

Replace the full contents of `SessionGrantS2C.java`:

```java
package com.pathongko.skein.platform.net.packet;

import com.pathongko.skein.platform.net.PacketCodec;
import com.pathongko.skein.platform.net.PacketReader;
import com.pathongko.skein.platform.net.PacketWriter;

/**
 * Server -> Client, login phase. If the handshake is compatible AND the player is
 * allowed to sync (see the whitelist gate on {@code SkeinServerSession}), the
 * server hands the client its "ticket": where the HTTP transfer endpoint is, a
 * short-lived session token, and the current pack version + root-manifest hash so
 * the client can compare against its cache. If not, {@code granted} is false and
 * only {@code deniedReason} is meaningful — the other fields are blank. See
 * design doc, Part 3, and docs/superpowers/specs/2026-08-28-whitelist-gated-sync-design.md.
 */
public record SessionGrantS2C(
        boolean granted,
        String transferEndpoint,
        String sessionToken,
        String packVersion,
        String rootManifestHash,
        String deniedReason) {

    public static SessionGrantS2C granted(String transferEndpoint, String sessionToken,
                                          String packVersion, String rootManifestHash) {
        return new SessionGrantS2C(true, transferEndpoint, sessionToken, packVersion, rootManifestHash, "");
    }

    public static SessionGrantS2C denied(String reason) {
        return new SessionGrantS2C(false, "", "", "", "", reason);
    }

    public static final PacketCodec<SessionGrantS2C> CODEC = new PacketCodec<>() {
        @Override
        public void encode(SessionGrantS2C p, PacketWriter w) {
            w.writeBoolean(p.granted())
                    .writeString(p.transferEndpoint())
                    .writeString(p.sessionToken())
                    .writeString(p.packVersion())
                    .writeString(p.rootManifestHash())
                    .writeString(p.deniedReason());
        }

        @Override
        public SessionGrantS2C decode(PacketReader r) {
            return new SessionGrantS2C(
                    r.readBoolean(),
                    r.readString(),
                    r.readString(),
                    r.readString(),
                    r.readString(),
                    r.readString());
        }
    };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :skein-platform:test --tests "*PacketCodecTest*"`
Expected: PASS (all three tests).

- [ ] **Step 5: Commit**

```bash
git add skein-platform/src/main/java/com/pathongko/skein/platform/net/packet/SessionGrantS2C.java skein-platform/src/test/java/com/pathongko/skein/platform/net/PacketCodecTest.java
git commit -m "feat(platform): add a granted/denied shape to SessionGrantS2C"
```

---

### Task 2: `MinecraftWhitelist` reader

**Files:**
- Create: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/MinecraftWhitelist.java`
- Test: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/MinecraftWhitelistTest.java`

**Interfaces:**
- Consumes: `com.pathongko.skein.common.json.Json.read(String)` (returns `Object`; a JSON array decodes to `List<Object>`, a JSON object element decodes to `Map<String,Object>`), `com.pathongko.skein.common.json.JsonException`.
- Produces: `new MinecraftWhitelist(Path file)`, `boolean contains(String uuid)` — used by Task 3.

- [ ] **Step 1: Write the failing test**

```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecraftWhitelistTest {

    @Test
    void containsReturnsTrueForAListedUuid(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("whitelist.json");
        Files.writeString(file, """
                [{"uuid":"c1c19f0b-1234-4a5b-8c9d-0e1f2a3b4c5d","name":"Steve"}]
                """);

        assertThat(new MinecraftWhitelist(file).contains("c1c19f0b-1234-4a5b-8c9d-0e1f2a3b4c5d")).isTrue();
    }

    @Test
    void containsReturnsFalseForAnUnlistedUuid(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("whitelist.json");
        Files.writeString(file, """
                [{"uuid":"c1c19f0b-1234-4a5b-8c9d-0e1f2a3b4c5d","name":"Steve"}]
                """);

        assertThat(new MinecraftWhitelist(file).contains("00000000-0000-0000-0000-000000000000")).isFalse();
    }

    @Test
    void comparisonIsCaseInsensitive(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("whitelist.json");
        Files.writeString(file, """
                [{"uuid":"C1C19F0B-1234-4A5B-8C9D-0E1F2A3B4C5D","name":"Steve"}]
                """);

        assertThat(new MinecraftWhitelist(file).contains("c1c19f0b-1234-4a5b-8c9d-0e1f2a3b4c5d")).isTrue();
    }

    @Test
    void emptyWhitelistContainsNobody(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("whitelist.json");
        Files.writeString(file, "[]");

        assertThat(new MinecraftWhitelist(file).contains("c1c19f0b-1234-4a5b-8c9d-0e1f2a3b4c5d")).isFalse();
    }

    @Test
    void missingFileFailsClosed(@TempDir Path dir) {
        Path file = dir.resolve("does-not-exist.json");

        assertThatCode(() -> new MinecraftWhitelist(file).contains("x")).doesNotThrowAnyException();
        assertThat(new MinecraftWhitelist(file).contains("x")).isFalse();
    }

    @Test
    void malformedJsonFailsClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("whitelist.json");
        Files.writeString(file, "{not valid json");

        assertThatCode(() -> new MinecraftWhitelist(file).contains("x")).doesNotThrowAnyException();
        assertThat(new MinecraftWhitelist(file).contains("x")).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-bootstrap:test --tests "*MinecraftWhitelistTest*"`
Expected: FAIL — `MinecraftWhitelist` class does not exist (compile error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.json.Json;
import com.pathongko.skein.common.json.JsonException;
import com.pathongko.skein.common.util.Preconditions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Reads a vanilla Minecraft {@code whitelist.json} to check whether a player UUID
 * is present. Re-reads the file on every check — it is tiny and this only runs
 * once per join attempt, so live edits (an admin running {@code /whitelist add})
 * take effect immediately with no caching/invalidation to get wrong.
 *
 * <p>Any read or parse failure is treated as "not whitelisted" (fail closed):
 * {@code SkeinServerSession} uses that the same way as a genuine non-member,
 * denying access rather than silently falling back to "allow everyone".
 */
public final class MinecraftWhitelist {

    private final Path file;

    public MinecraftWhitelist(Path file) {
        this.file = Preconditions.requireNonNull(file, "file");
    }

    public boolean contains(String uuid) {
        Preconditions.requireNonNull(uuid, "uuid");
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Object parsed = Json.read(text);
            if (!(parsed instanceof List<?> entries)) {
                return false;
            }
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map
                        && uuid.equalsIgnoreCase(String.valueOf(map.get("uuid")))) {
                    return true;
                }
            }
            return false;
        } catch (IOException | JsonException | RuntimeException e) {
            System.err.println("[Skein] could not read whitelist " + file + ": " + e.getMessage());
            return false;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :skein-bootstrap:test --tests "*MinecraftWhitelistTest*"`
Expected: PASS (all six tests).

- [ ] **Step 5: Commit**

```bash
git add skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/MinecraftWhitelist.java skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/MinecraftWhitelistTest.java
git commit -m "feat(bootstrap): add MinecraftWhitelist, a vanilla whitelist.json reader"
```

---

### Task 3: Gate `SkeinServerSession.onHandshake` on player identity

**Files:**
- Modify: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinServerSession.java`
- Modify: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/ServerBootstrap.java`
- Modify: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/ServerHandshakeTest.java`

**Interfaces:**
- Consumes: `SessionGrantS2C.granted(...)`/`.denied(...)` (Task 1), `MinecraftWhitelist.contains(String)` (Task 2), `com.pathongko.skein.platform.net.PlayerRef` (existing, has `String id()`).
- Produces: `new SkeinServerSession(String transferEndpoint, Optional<String> token, Supplier<GeneratedPack> pack, HashService hash, BooleanSupplier whitelistGateEnabled, MinecraftWhitelist whitelist)`, `SessionGrantS2C onHandshake(PlayerRef sender, HandshakeReplyC2S reply)` — used by Task 8 (`SkeinMod`).

- [ ] **Step 1: Write the failing test**

Replace `ServerHandshakeTest.java` in full:

```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import com.pathongko.skein.server.manifest.GeneratedPack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerHandshakeTest {

    private static SkeinServerSession sessionWithGate(GeneratedPack pack, boolean gateEnabled,
                                                       MinecraftWhitelist whitelist) {
        return new SkeinServerSession("http://example:25585", Optional.of("tok"), () -> pack,
                new MessageDigestHashService(), () -> gateEnabled, whitelist);
    }

    @Test
    void grantCarriesEndpointAndRootHashWhenGateIsOff() {
        GeneratedPack pack = TestPacks.smallPack(); // helper building a GeneratedPack
        var hash = new MessageDigestHashService();
        SkeinServerSession session = sessionWithGate(pack, false, whitelistThatDenies());

        SessionGrantS2C grant = session.onHandshake(new NamedPlayer("p1"), new HandshakeReplyC2S(true, 1, "0.0.1"));

        assertThat(grant.granted()).isTrue();
        assertThat(grant.transferEndpoint()).isEqualTo("http://example:25585");
        assertThat(grant.sessionToken()).isEqualTo("tok");
        assertThat(grant.packVersion()).isEqualTo(pack.root().packVersion().toString());
        assertThat(grant.rootManifestHash())
                .isEqualTo(hash.hash(pack.encodedRoot(), HashAlgorithm.SHA256).value());
    }

    @Test
    void gateOnAndWhitelistedPlayerIsGranted(@TempDir Path dir) throws Exception {
        GeneratedPack pack = TestPacks.smallPack();
        Path whitelistFile = dir.resolve("whitelist.json");
        Files.writeString(whitelistFile, "[{\"uuid\":\"p1\",\"name\":\"Steve\"}]");
        SkeinServerSession session = sessionWithGate(pack, true, new MinecraftWhitelist(whitelistFile));

        SessionGrantS2C grant = session.onHandshake(new NamedPlayer("p1"), new HandshakeReplyC2S(true, 1, "0.0.1"));

        assertThat(grant.granted()).isTrue();
    }

    @Test
    void gateOnAndUnlistedPlayerIsDenied(@TempDir Path dir) throws Exception {
        GeneratedPack pack = TestPacks.smallPack();
        Path whitelistFile = dir.resolve("whitelist.json");
        Files.writeString(whitelistFile, "[{\"uuid\":\"someone-else\",\"name\":\"Alex\"}]");
        SkeinServerSession session = sessionWithGate(pack, true, new MinecraftWhitelist(whitelistFile));

        SessionGrantS2C grant = session.onHandshake(new NamedPlayer("p1"), new HandshakeReplyC2S(true, 1, "0.0.1"));

        assertThat(grant.granted()).isFalse();
        assertThat(grant.deniedReason()).isNotBlank();
    }

    @Test
    void gateOnAndUnreadableWhitelistFailsClosed(@TempDir Path dir) {
        GeneratedPack pack = TestPacks.smallPack();
        Path missing = dir.resolve("does-not-exist.json");
        SkeinServerSession session = sessionWithGate(pack, true, new MinecraftWhitelist(missing));

        SessionGrantS2C grant = session.onHandshake(new NamedPlayer("p1"), new HandshakeReplyC2S(true, 1, "0.0.1"));

        assertThat(grant.granted()).isFalse();
    }

    @Test
    void bootstrapRepliesToSender() {
        GeneratedPack pack = TestPacks.smallPack();
        RecordingChannel channel = new RecordingChannel();
        SkeinServerSession session = sessionWithGate(pack, false, whitelistThatDenies());
        new ServerBootstrap(channel, session).install();

        channel.deliver(new NamedPlayer("p1"),
                HandshakeReplyC2S.CODEC.toBytes(new HandshakeReplyC2S(true, 1, "0.0.1")));

        assertThat(channel.toPlayer).hasSize(1);
        SessionGrantS2C grant = SessionGrantS2C.CODEC.fromBytes(channel.toPlayer.get(0).payload());
        assertThat(grant.transferEndpoint()).isEqualTo("http://example:25585");
    }

    private static MinecraftWhitelist whitelistThatDenies() {
        // A whitelist backed by a file that doesn't exist: MinecraftWhitelist.contains
        // fails closed (false) for it, same as it would for a genuinely unlisted
        // player. Used only in gate-off tests, where the grant must be true
        // regardless of what the whitelist would say.
        return new MinecraftWhitelist(Path.of("nonexistent-whitelist.json"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-bootstrap:test --tests "*ServerHandshakeTest*"`
Expected: FAIL — `SkeinServerSession`'s constructor and `onHandshake` don't match the new call shapes (compile error).

- [ ] **Step 3: Write minimal implementation**

Replace `SkeinServerSession.java` in full:

```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.platform.net.PlayerRef;
import com.pathongko.skein.platform.net.packet.HandshakeReplyC2S;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import com.pathongko.skein.server.manifest.GeneratedPack;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Server-side handshake logic: turn a client reply into a session grant. */
public final class SkeinServerSession {

    private final String transferEndpoint;
    private final Optional<String> token;
    private final Supplier<GeneratedPack> pack;
    private final HashService hash;
    private final BooleanSupplier whitelistGateEnabled;
    private final MinecraftWhitelist whitelist;

    public SkeinServerSession(String transferEndpoint, Optional<String> token,
                              Supplier<GeneratedPack> pack, HashService hash,
                              BooleanSupplier whitelistGateEnabled, MinecraftWhitelist whitelist) {
        this.transferEndpoint = Preconditions.requireNonBlank(transferEndpoint, "transferEndpoint");
        this.token = Preconditions.requireNonNull(token, "token");
        this.pack = Preconditions.requireNonNull(pack, "pack");
        this.hash = Preconditions.requireNonNull(hash, "hash");
        this.whitelistGateEnabled = Preconditions.requireNonNull(whitelistGateEnabled, "whitelistGateEnabled");
        this.whitelist = Preconditions.requireNonNull(whitelist, "whitelist");
    }

    public SessionGrantS2C onHandshake(PlayerRef sender, HandshakeReplyC2S reply) {
        Preconditions.requireNonNull(sender, "sender");
        Preconditions.requireNonNull(reply, "reply");

        if (whitelistGateEnabled.getAsBoolean() && !whitelist.contains(sender.id())) {
            return SessionGrantS2C.denied(
                    "You are not on this server's whitelist. Ask an admin to add you.");
        }

        GeneratedPack current = pack.get();
        String rootHash = hash.hash(current.encodedRoot(), HashAlgorithm.SHA256).value();
        return SessionGrantS2C.granted(
                transferEndpoint,
                token.orElse(""),
                current.root().packVersion().toString(),
                rootHash);
    }
}
```

Then update `ServerBootstrap.java`'s `install()` to thread `sender` through — replace the method body:

```java
    public void install() {
        channel.onReceive((sender, payload) -> {
            HandshakeReplyC2S reply = HandshakeReplyC2S.CODEC.fromBytes(payload);
            SessionGrantS2C grant = session.onHandshake(sender, reply);
            channel.sendToPlayer(sender, SessionGrantS2C.CODEC.toBytes(grant));
        });
    }
```

(Only that method body changes; the rest of `ServerBootstrap.java` — imports, fields, constructor — stays as-is, since `sender` was already the first lambda parameter, just unused before now.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :skein-bootstrap:test --tests "*ServerHandshakeTest*"`
Expected: PASS (all five tests).

- [ ] **Step 5: Run the full skein-bootstrap suite to check for regressions**

Run: `./gradlew :skein-bootstrap:test`
Expected: PASS. (`ClientBootstrapTest` and `SkeinJoinSyncTest` will fail to *compile* here because Task 7 hasn't updated `ClientBootstrapTest`'s use of the old `SessionGrantS2C` constructor yet — if so, that's expected and resolved by Task 7; note it and continue, don't attempt to fix `ClientBootstrapTest` in this task.)

- [ ] **Step 6: Commit**

```bash
git add skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/SkeinServerSession.java skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/ServerBootstrap.java skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/ServerHandshakeTest.java
git commit -m "feat(bootstrap): gate handshake grants on a whitelist check"
```

---

### Task 4: `ServerNode` whitelist-gate toggle with marker-file persistence

**Files:**
- Modify: `skein-server/src/main/java/com/pathongko/skein/server/manage/ServerNode.java`
- Create: `skein-server/src/test/java/com/pathongko/skein/server/manage/ServerNodeTest.java`

**Interfaces:**
- Consumes: `ManagedPack.packDir()` (existing).
- Produces: `ServerNode.whitelistGateEnabled()` (boolean), `ServerNode.setWhitelistGateEnabled(boolean)` — used by Task 5 (`AdminSurface`) and Task 8 (`SkeinMod`, via `node::whitelistGateEnabled` as a `BooleanSupplier`).

- [ ] **Step 1: Write the failing test**

```java
package com.pathongko.skein.server.manage;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.server.http.SkeinHttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerNodeTest {

    private static final HashService HASH = new MessageDigestHashService();
    private static final JsonManifestCodec CODEC = new JsonManifestCodec();

    private static Map<FileCategory, String> dirs() {
        Map<FileCategory, String> dirs = new EnumMap<>(FileCategory.class);
        dirs.put(FileCategory.MOD, "mods");
        return dirs;
    }

    private static ManagedPack pack(Path packDir) {
        return new ManagedPack(packDir, "test", PackVersion.of("1.0.0"), HashAlgorithm.SHA256,
                "http://127.0.0.1:1/files/", dirs(), HASH, CODEC);
    }

    private static ServerNode node(Path packDir) {
        ManagedPack pack = pack(packDir);
        SkeinHttpServer fakeServer = new SkeinHttpServer(
                pack::current, Optional.empty(), Optional.empty(), new InetSocketAddress(0));
        return new ServerNode("test", pack, 0, Optional.empty(), fakeServer);
    }

    @Test
    void defaultsToDisabled(@TempDir Path dir) {
        assertThat(node(dir.resolve("pack")).whitelistGateEnabled()).isFalse();
    }

    @Test
    void enablingWritesAMarkerFileUnderThePackDir(@TempDir Path dir) {
        Path packDir = dir.resolve("pack");
        ServerNode node = node(packDir);

        node.setWhitelistGateEnabled(true);

        assertThat(node.whitelistGateEnabled()).isTrue();
        assertThat(packDir.resolve(".whitelist-gate-enabled")).exists();
    }

    @Test
    void disablingRemovesTheMarkerFile(@TempDir Path dir) {
        Path packDir = dir.resolve("pack");
        ServerNode node = node(packDir);
        node.setWhitelistGateEnabled(true);

        node.setWhitelistGateEnabled(false);

        assertThat(node.whitelistGateEnabled()).isFalse();
        assertThat(packDir.resolve(".whitelist-gate-enabled")).doesNotExist();
    }

    @Test
    void aFreshServerNodeRestoresStateFromAnExistingMarkerFile(@TempDir Path dir) {
        Path packDir = dir.resolve("pack");
        node(packDir).setWhitelistGateEnabled(true);

        // Simulates a restart: a brand new ServerNode over the same packDir.
        ServerNode restarted = node(packDir);

        assertThat(restarted.whitelistGateEnabled()).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-server:test --tests "*ServerNodeTest*"`
Expected: FAIL — `whitelistGateEnabled()`/`setWhitelistGateEnabled(boolean)` don't exist yet (compile error).

- [ ] **Step 3: Write minimal implementation**

Replace `ServerNode.java` in full:

```java
package com.pathongko.skein.server.manage;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.server.http.SkeinHttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One managed server in the control plane: a named {@link ManagedPack} plus the
 * running {@link SkeinHttpServer} that serves it on its own port. The control
 * plane can hold several of these, so one admin dashboard manages many packs.
 */
public final class ServerNode {

    private static final String WHITELIST_GATE_MARKER = ".whitelist-gate-enabled";

    private final String name;
    private final ManagedPack pack;
    private final int port;
    private final Optional<String> clientToken;
    private final SkeinHttpServer server;
    private final AtomicBoolean whitelistGateEnabled;

    public ServerNode(String name, ManagedPack pack, int port,
                      Optional<String> clientToken, SkeinHttpServer server) {
        this.name = Preconditions.requireNonBlank(name, "name");
        this.pack = Preconditions.requireNonNull(pack, "pack");
        this.port = port;
        this.clientToken = Preconditions.requireNonNull(clientToken, "clientToken");
        this.server = Preconditions.requireNonNull(server, "server");
        this.whitelistGateEnabled = new AtomicBoolean(Files.exists(whitelistGateMarker()));
    }

    public String name() {
        return name;
    }

    public ManagedPack pack() {
        return pack;
    }

    public int port() {
        return port;
    }

    public boolean clientTokenRequired() {
        return clientToken.isPresent();
    }

    /**
     * Whether sync is restricted to players on this server's Minecraft whitelist.
     * Defaults to {@code false} and, once set, survives a restart via a marker
     * file under {@link ManagedPack#packDir()} — see
     * docs/superpowers/specs/2026-08-28-whitelist-gated-sync-design.md for why a
     * security-relevant toggle must not silently reset to "open" on every boot.
     */
    public boolean whitelistGateEnabled() {
        return whitelistGateEnabled.get();
    }

    public void setWhitelistGateEnabled(boolean enabled) {
        whitelistGateEnabled.set(enabled);
        Path marker = whitelistGateMarker();
        try {
            if (enabled) {
                Files.createDirectories(pack.packDir());
                if (!Files.exists(marker)) {
                    Files.createFile(marker);
                }
            } else {
                Files.deleteIfExists(marker);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to persist whitelist gate setting under " + pack.packDir(), e);
        }
    }

    private Path whitelistGateMarker() {
        return pack.packDir().resolve(WHITELIST_GATE_MARKER);
    }

    public void stop() {
        server.stop();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :skein-server:test --tests "*ServerNodeTest*"`
Expected: PASS (all four tests).

- [ ] **Step 5: Commit**

```bash
git add skein-server/src/main/java/com/pathongko/skein/server/manage/ServerNode.java skein-server/src/test/java/com/pathongko/skein/server/manage/ServerNodeTest.java
git commit -m "feat(server): add a restart-durable whitelist-gate toggle to ServerNode"
```

---

### Task 5: `AdminSurface` whitelist-gate endpoint

**Files:**
- Modify: `skein-server/src/main/java/com/pathongko/skein/server/admin/AdminSurface.java`
- Modify: `skein-server/src/test/java/com/pathongko/skein/server/AdminSurfaceIntegrationTest.java`

**Interfaces:**
- Consumes: `ServerNode.whitelistGateEnabled()`/`setWhitelistGateEnabled(boolean)` (Task 4).
- Produces: `POST /admin/api/whitelist-gate?server=<name>` with JSON body `{"enabled": true|false}`, response is the same shape as `packJson`; `GET /admin/api/pack?server=<name>` and `GET /admin/api/servers` responses gain a `"whitelistGateEnabled"` boolean field — used by Task 6 (dashboard).

- [ ] **Step 1: Write the failing test**

Add this test to `AdminSurfaceIntegrationTest.java` (append inside the class, alongside the existing tests):

```java
    @Test
    void whitelistGateCanBeToggledAndIsReflectedInPackJson() throws Exception {
        String token = login("secret");
        assertThat(get("/admin/api/pack?server=test", token).body())
                .contains("\"whitelistGateEnabled\":false");

        HttpResponse<String> res = post(
                "/admin/api/whitelist-gate?server=test", token, "{\"enabled\":true}");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"whitelistGateEnabled\":true");
        assertThat(get("/admin/api/pack?server=test", token).body())
                .contains("\"whitelistGateEnabled\":true");
        assertThat(get("/admin/api/servers", token).body())
                .contains("\"whitelistGateEnabled\":true");
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-server:test --tests "*AdminSurfaceIntegrationTest*"`
Expected: FAIL — the new test's assertions fail (no `whitelist-gate` route yet: 404; `whitelistGateEnabled` missing from JSON).

- [ ] **Step 3: Write minimal implementation**

In `AdminSurface.java`, add a case to the `switch` inside `handle(...)`:

```java
                case "version" -> handleSetVersion(exchange);
                case "whitelist-gate" -> handleSetWhitelistGate(exchange);
```

Add the handler method near `handleSetVersion`:

```java
    private void handleSetWhitelistGate(HttpExchange exchange) throws IOException {
        Map<String, Object> body = readJsonObject(exchange);
        ServerNode node = resolveNode(exchange, body);
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        node.setWhitelistGateEnabled(enabled);
        sendJson(exchange, 200, packJson(node));
    }
```

Add the field to both `serversJson()` and `packJson()` (one `s.put`/`out.put` line each, next to the existing `clientTokenRequired` line):

In `serversJson()`:
```java
            s.put("clientTokenRequired", node.clientTokenRequired());
            s.put("whitelistGateEnabled", node.whitelistGateEnabled());
```

In `packJson()`:
```java
        out.put("clientTokenRequired", node.clientTokenRequired());
        out.put("whitelistGateEnabled", node.whitelistGateEnabled());
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :skein-server:test --tests "*AdminSurfaceIntegrationTest*"`
Expected: PASS (all tests in the class, including the new one).

- [ ] **Step 5: Commit**

```bash
git add skein-server/src/main/java/com/pathongko/skein/server/admin/AdminSurface.java skein-server/src/test/java/com/pathongko/skein/server/AdminSurfaceIntegrationTest.java
git commit -m "feat(server): expose the whitelist gate over the admin API"
```

---

### Task 6: Admin dashboard checkbox

**Files:**
- Modify: `skein-server/src/main/resources/admin/index.html`

**Interfaces:**
- Consumes: `p.whitelistGateEnabled` from the `GET /admin/api/pack?server=X` response (Task 5), and calls `POST /admin/api/whitelist-gate?server=X` (Task 5).

There is no automated test for this file (it's a dependency-free static HTML page with inline JS, and the project has no browser test harness); verification is manual against a running `SkeinHttpServer`+`AdminSurface` (or the standalone `SkeinCli` tool) per the steps below.

- [ ] **Step 1: Add the checkbox to the "Pack version" panel**

In `index.html`, change the existing panel:

```html
      <div class="panel">
        <h3>Pack version</h3>
        <div class="row">
          <input id="version" class="grow" type="text" placeholder="e.g. 2026.07.05-1">
          <button class="primary" onclick="saveVersion()">Save version</button>
          <button onclick="regenerate()">Rescan &amp; regenerate</button>
        </div>
        <p class="faint" style="margin:9px 0 0">Any change makes clients re-check on their next join.</p>
      </div>
```

to:

```html
      <div class="panel">
        <h3>Pack version</h3>
        <div class="row">
          <input id="version" class="grow" type="text" placeholder="e.g. 2026.07.05-1">
          <button class="primary" onclick="saveVersion()">Save version</button>
          <button onclick="regenerate()">Rescan &amp; regenerate</button>
        </div>
        <p class="faint" style="margin:9px 0 0">Any change makes clients re-check on their next join.</p>
      </div>

      <div class="panel">
        <h3>Access</h3>
        <label class="row" style="gap:8px; cursor:pointer">
          <input id="whitelistGate" type="checkbox" style="width:auto" onchange="setWhitelistGate()">
          Restrict sync to players on this server's Minecraft whitelist
        </label>
        <p class="faint" style="margin:9px 0 0">Applies immediately, and survives a server restart.</p>
      </div>
```

- [ ] **Step 2: Render current state and wire the change handler**

In `renderMain(p)` (inside the `<script>` block), add one line setting the checkbox from the API response — right next to the existing `document.getElementById('version').value=p.version;` line:

```js
  document.getElementById('version').value=p.version;
  document.getElementById('whitelistGate').checked=!!p.whitelistGateEnabled;
```

Add a new function next to `saveVersion()`:

```js
async function setWhitelistGate(){
  const enabled=document.getElementById('whitelistGate').checked;
  try{await api('whitelist-gate?server='+encodeURIComponent(selected),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({enabled})});
    toast(enabled?'Whitelist gate on':'Whitelist gate off'); loadPack(); loadServers();}
  catch(e){document.getElementById('whitelistGate').checked=!enabled; toast(e.message,true);}
}
```

- [ ] **Step 3: Manual verification**

Run: `./gradlew :skein-server:build`
Expected: builds clean (this file is a resource, not compiled, but this confirms the module still packages correctly with the edited resource).

Then, with `skein-forge` running against a real dedicated server (or `SkeinCli` standalone — see `skein-cli`'s existing usage docs), open `/admin`, select a server, and confirm: the checkbox reflects the persisted state on page load, toggling it flips access immediately for the next handshake, and the state survives a server restart (this exercises Task 4's marker file end-to-end through the UI).

- [ ] **Step 4: Commit**

```bash
git add skein-server/src/main/resources/admin/index.html
git commit -m "feat(server): add a whitelist-gate checkbox to the admin dashboard"
```

---

### Task 7: Client-side denial handling

**Files:**
- Modify: `skein-core/src/main/java/com/pathongko/skein/core/error/ErrorCode.java`
- Modify: `skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/ClientBootstrap.java`
- Modify: `skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/ClientBootstrapTest.java`

**Interfaces:**
- Consumes: `SessionGrantS2C.granted()`/`.deniedReason()` (Task 1), `SyncOutcome.Failed(ErrorCode, Recoverability, String)` (existing), `SyncEvent.SyncFinished(SyncOutcome)` (existing), `SyncProgressListener.onEvent(SyncEvent)` (existing) — reuses `SkeinProgressScreen`'s existing terminal-`Failed` rendering (back button + message), no new client UI class needed.

- [ ] **Step 1: Write the failing test**

Replace `ClientBootstrapTest.java` in full:

```java
package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.sync.SyncEvent;
import com.pathongko.skein.core.sync.SyncOutcome;
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
                SessionGrantS2C.granted("http://127.0.0.1:1", "", "1.0.0", "x")));

        assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).isNotEmpty();
    }

    @Test
    void inboundDenialReportsFailureWithoutRunningASession(@TempDir java.nio.file.Path dir) {
        RecordingChannel channel = new RecordingChannel();
        FakeClientPlatform platform = new FakeClientPlatform(dir, channel);
        List<SyncEvent> events = new ArrayList<>();
        ClientBootstrap boot = new ClientBootstrap(platform, Runnable::run, events::add);
        boot.install();

        channel.deliver(null, SessionGrantS2C.CODEC.toBytes(
                SessionGrantS2C.denied("You are not on this server's whitelist.")));

        assertThat(events).hasSize(1);
        SyncEvent.SyncFinished finished = (SyncEvent.SyncFinished) events.get(0);
        assertThat(finished.outcome()).isInstanceOf(SyncOutcome.Failed.class);
        SyncOutcome.Failed failed = (SyncOutcome.Failed) finished.outcome();
        assertThat(failed.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThat(failed.detail()).isEqualTo("You are not on this server's whitelist.");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :skein-bootstrap:test --tests "*ClientBootstrapTest*"`
Expected: FAIL — `ErrorCode.ACCESS_DENIED` doesn't exist (compile error), and (once that's briefly stubbed) the denial test fails because `ClientBootstrap` still tries to run a session for every grant regardless of `granted()`.

- [ ] **Step 3: Write minimal implementation**

In `ErrorCode.java`, add one enum constant (after the existing ones):

```java
public enum ErrorCode {
    MANIFEST_UNAVAILABLE,
    MANIFEST_INVALID,
    DOWNLOAD_FAILED,
    HASH_MISMATCH,
    APPLY_FAILED,
    LOCAL_IO,
    CANCELLED,
    ACCESS_DENIED
}
```

(Keep whatever trailing members/semicolon the file already has after `CANCELLED` — just insert `ACCESS_DENIED` as the new last constant.)

Replace `ClientBootstrap.java`'s `install()` method and add the needed imports:

```java
package com.pathongko.skein.bootstrap;

import com.pathongko.skein.common.util.Preconditions;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.error.Recoverability;
import com.pathongko.skein.core.sync.SyncEvent;
import com.pathongko.skein.core.sync.SyncOutcome;
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
            if (!grant.granted()) {
                reportDenial(grant.deniedReason());
                return;
            }
            syncExecutor.execute(() -> new SkeinClientSession(platform, listener).run(grant));
        });
    }

    private void reportDenial(String reason) {
        SyncOutcome denied = new SyncOutcome.Failed(ErrorCode.ACCESS_DENIED, Recoverability.NEEDS_ADMIN, reason);
        try {
            listener.onEvent(new SyncEvent.SyncFinished(denied));
        } catch (RuntimeException ignored) {
            // listener is display code; its failure must not break this handler
        }
    }

    public void sendHandshakeReply() {
        String modVersion = platform.loaderInfo().skeinVersion();
        HandshakeReplyC2S reply = new HandshakeReplyC2S(true, PROTOCOL_VERSION, modVersion);
        platform.controlChannel().sendToServer(HandshakeReplyC2S.CODEC.toBytes(reply));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :skein-bootstrap:test --tests "*ClientBootstrapTest*"`
Expected: PASS (all three tests).

- [ ] **Step 5: Run the full skein-bootstrap suite to check for regressions from Tasks 1-7**

Run: `./gradlew :skein-bootstrap:test`
Expected: PASS — this is the first point where every `skein-bootstrap` test compiles and runs together again (Task 3's step 5 flagged `ClientBootstrapTest` as expected-broken until this task).

- [ ] **Step 6: Commit**

```bash
git add skein-core/src/main/java/com/pathongko/skein/core/error/ErrorCode.java skein-bootstrap/src/main/java/com/pathongko/skein/bootstrap/ClientBootstrap.java skein-bootstrap/src/test/java/com/pathongko/skein/bootstrap/ClientBootstrapTest.java
git commit -m "feat(bootstrap): report a whitelist denial as a Failed/NEEDS_ADMIN outcome"
```

---

### Task 8: Wire it all into `SkeinMod` (both loaders)

**Files:**
- Modify: `skein-forge/src/main/java/com/pathongko/skein/forge/SkeinMod.java`
- Modify: `skein-neoforge/src/main/java/com/pathongko/skein/neoforge/SkeinMod.java`

**Interfaces:**
- Consumes: `new MinecraftWhitelist(Path)` (Task 2), `new SkeinServerSession(..., BooleanSupplier, MinecraftWhitelist)` (Task 3), `ServerNode.whitelistGateEnabled()` used as a `BooleanSupplier` (Task 4).
- Produces: nothing further — this is the final wiring task; the dedicated server now enforces the gate end-to-end.

- [ ] **Step 1: Edit `skein-forge`'s `SkeinMod.java`**

Add the import:

```java
import com.pathongko.skein.bootstrap.MinecraftWhitelist;
```

Replace `startServer()`'s body from the `ServerNode` registration onward — this is the tail of the method, after `controlPlane.register(...)`:

```java
        SkeinHttpServer httpServer = new SkeinHttpServer(
                managed::current, cfg.clientToken(), admin, new InetSocketAddress(cfg.httpPort()));
        int boundPort = httpServer.start();
        ServerNode node = new ServerNode(SERVER_PACK_ID, managed, boundPort, cfg.clientToken(), httpServer);
        controlPlane.register(node);

        // whitelist.json lives one level above the config dir — the same "server
        // root" convention SkeinServerConfig's default packDir already uses —
        // independent of wherever an operator may have redirected packDir itself.
        Path whitelistFile = configDir.resolve("..").normalize().resolve("whitelist.json");
        MinecraftWhitelist whitelist = new MinecraftWhitelist(whitelistFile);

        new ServerBootstrap(controlChannel,
                new SkeinServerSession(cfg.publicEndpoint(), cfg.clientToken(), managed::current,
                        new MessageDigestHashService(), node::whitelistGateEnabled, whitelist))
                .install();
```

(Everything above that tail — `configDir`, `cfg`, `baseUrl`, `managed`, `controlPlane`, `admin`, up through `httpServer` construction — stays exactly as it already is; only the `controlPlane.register(...)` line changes to capture `node`, and the final `ServerBootstrap`/`SkeinServerSession` construction gains the two new arguments plus the `whitelistFile`/`whitelist` lines just above it.)

- [ ] **Step 2: Verify skein-forge builds**

Run: `./gradlew :skein-forge:build`
Expected: BUILD SUCCESSFUL — this is the same verification bar CLAUDE.md requires for every `skein-forge` change (this environment has already confirmed this command works end-to-end).

- [ ] **Step 3: Mirror the identical edit into `skein-neoforge`'s `SkeinMod.java`**

The NeoForge file's `startServer()` is line-for-line identical to Forge's at this point (verified by reading both files during planning). Add the same import:

```java
import com.pathongko.skein.bootstrap.MinecraftWhitelist;
```

And replace `startServer()`'s tail the same way, from the `ServerNode` registration onward:

```java
        SkeinHttpServer httpServer = new SkeinHttpServer(
                managed::current, cfg.clientToken(), admin, new InetSocketAddress(cfg.httpPort()));
        int boundPort = httpServer.start();
        ServerNode node = new ServerNode(SERVER_PACK_ID, managed, boundPort, cfg.clientToken(), httpServer);
        controlPlane.register(node);

        // whitelist.json lives one level above the config dir — the same "server
        // root" convention SkeinServerConfig's default packDir already uses —
        // independent of wherever an operator may have redirected packDir itself.
        Path whitelistFile = configDir.resolve("..").normalize().resolve("whitelist.json");
        MinecraftWhitelist whitelist = new MinecraftWhitelist(whitelistFile);

        new ServerBootstrap(controlChannel,
                new SkeinServerSession(cfg.publicEndpoint(), cfg.clientToken(), managed::current,
                        new MessageDigestHashService(), node::whitelistGateEnabled, whitelist))
                .install();
```

(Everything above that tail — `configDir`, `cfg`, `baseUrl`, `managed`, `controlPlane`, `admin`, up through `httpServer` construction — stays exactly as it already is in the NeoForge file too; only the `controlPlane.register(...)` line and the final `ServerBootstrap`/`SkeinServerSession` construction change, identically to the Forge edit above.)

- [ ] **Step 4: Note the verification limit**

Do NOT run `./gradlew :skein-neoforge:build` expecting it to prove anything — `skein-neoforge` is commented out of `settings.gradle.kts` and its NeoGradle toolchain has not been exercised in this environment (see CLAUDE.md, "Loader adapter modules"). This mirrors the existing, already-accepted state of that module: every other server-side change in `SkeinMod.java` for NeoForge is equally unverified. Do not uncomment `skein-neoforge` in `settings.gradle.kts` as part of this task — that's a separate decision outside this plan's scope.

- [ ] **Step 5: Commit**

```bash
git add skein-forge/src/main/java/com/pathongko/skein/forge/SkeinMod.java skein-neoforge/src/main/java/com/pathongko/skein/neoforge/SkeinMod.java
git commit -m "feat(forge,neoforge): wire the whitelist gate into the dedicated-server Composition Root"
```

---

## Final Verification

- [ ] Run the full shared-module suite: `./gradlew build` — expect all existing tests plus every new test above to pass (60+ pre-existing JUnit tests, per CLAUDE.md, plus the ~20 new ones across Tasks 1-7).
- [ ] Run `./gradlew :skein-forge:build` one more time standalone to reconfirm green after all tasks land together.
- [ ] Re-read `docs/superpowers/specs/2026-08-28-whitelist-gated-sync-design.md` and confirm every section has a corresponding task above: packet shape (Task 1), whitelist reader (Task 2), handshake gating (Task 3), `ServerNode`/persistence (Task 4), admin API (Task 5), dashboard (Task 6), client denial handling (Task 7), loader wiring (Task 8). No gaps.
