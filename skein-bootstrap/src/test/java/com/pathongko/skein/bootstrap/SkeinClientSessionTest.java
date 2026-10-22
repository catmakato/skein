package com.pathongko.skein.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.pathongko.skein.bootstrap.support.LocalPackServer;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.error.ErrorCode;
import com.pathongko.skein.core.sync.SyncEvent;
import com.pathongko.skein.core.sync.SyncOutcome;
import com.pathongko.skein.platform.net.packet.SessionGrantS2C;
import java.nio.charset.StandardCharsets;
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

            SyncOutcome outcome = session.run(SessionGrantS2C.granted(
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
            SessionGrantS2C grant = SessionGrantS2C.granted(
                    server.endpoint(), "", "1.0.0", server.rootHash());
            new SkeinClientSession(platform, e -> {}).run(grant);

            List<Object> seen = new ArrayList<>();
            SyncOutcome second = new SkeinClientSession(platform, seen::add).run(grant);
            assertThat(second).isInstanceOf(SyncOutcome.UpToDate.class);
            assertThat(seen).anySatisfy(event -> {
                assertThat(event).isInstanceOf(SyncEvent.SyncFinished.class);
                assertThat(((SyncEvent.SyncFinished) event).outcome())
                        .isInstanceOf(SyncOutcome.UpToDate.class);
            });
        }
    }

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

            SessionGrantS2C grant = SessionGrantS2C.granted(server.endpoint(), "", "1.0.0", server.rootHash());
            SyncOutcome outcome = new SkeinClientSession(platform, e -> {}).run(grant);

            assertThat(outcome.isSuccess()).isTrue();
            assertThat(Files.exists(oldBatch)).isFalse();
        }
    }

    @Test
    void deniedGrantFailsFastWithoutAttemptingASync(@TempDir Path root) {
        FakeClientPlatform platform = new FakeClientPlatform(root.resolve("client"));
        SyncOutcome outcome = new SkeinClientSession(platform, e -> {})
                .run(SessionGrantS2C.denied("some reason"));

        assertThat(outcome).isInstanceOf(SyncOutcome.Failed.class);
        SyncOutcome.Failed failed = (SyncOutcome.Failed) outcome;
        assertThat(failed.code()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThat(failed.detail()).isEqualTo("some reason");
    }

    @Test
    void failsGracefullyOnUnreachableServer(@TempDir Path root) {
        FakeClientPlatform platform = new FakeClientPlatform(root.resolve("client"));
        SyncOutcome outcome = new SkeinClientSession(platform, e -> {})
                .run(SessionGrantS2C.granted("http://127.0.0.1:1", "", "1.0.0", "deadbeef"));
        assertThat(outcome.isSuccess()).isFalse();
    }

    @Test
    void secondSyncQuarantinesAModDroppedFromThePackButKeepsAPlayerAddedMod(@TempDir Path root) throws Exception {
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");
        try (LocalPackServer server = new LocalPackServer(packDir)) {
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);
            SessionGrantS2C grant = SessionGrantS2C.granted(server.endpoint(), "", "1.0.0", server.rootHash());

            SyncOutcome first = new SkeinClientSession(platform, e -> {}).run(grant);
            assertThat(first).isInstanceOf(SyncOutcome.RestartRequired.class);

            Path playerMod = gameDir.resolve("mods").resolve("player-added.jar");
            Files.createDirectories(playerMod.getParent());
            Files.writeString(playerMod, "not from the pack");

            server.pack().deleteFile(new RelativePath("mods/example.jar"));
            SessionGrantS2C secondGrant = SessionGrantS2C.granted(
                    server.endpoint(), "", "1.0.0", server.rootHash());

            SyncOutcome second = new SkeinClientSession(platform, e -> {}).run(secondGrant);

            assertThat(second.isSuccess()).isTrue();
            assertThat(Files.exists(gameDir.resolve("mods").resolve("example.jar"))).isFalse();
            assertThat(Files.exists(playerMod)).isTrue();
            // "never a hard delete" (plan's explicit constraint): the dropped mod must
            // have been moved under .skein/quarantine/<batch>/, not erased outright.
            assertThat(findUnderQuarantine(gameDir, "example.jar")).isPresent();
        }
    }

    /** Searches .skein/quarantine/**​/{fileName} -- the batch dir name is timestamp-based and unpredictable. */
    private static java.util.Optional<Path> findUnderQuarantine(Path gameDir, String fileName) throws Exception {
        Path quarantineRoot = gameDir.resolve(".skein").resolve("quarantine");
        if (!Files.isDirectory(quarantineRoot)) {
            return java.util.Optional.empty();
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(quarantineRoot)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst();
        }
    }

    @Test
    void ledgerSurvivesAFailedSyncAndAppliesCorrectlyOnTheNextSuccess(@TempDir Path root) throws Exception {
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");
        try (LocalPackServer server = new LocalPackServer(packDir)) {
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);
            SessionGrantS2C grant = SessionGrantS2C.granted(server.endpoint(), "", "1.0.0", server.rootHash());

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

            SessionGrantS2C thirdGrant = SessionGrantS2C.granted(
                    server.endpoint(), "", "1.0.0", server.rootHash());
            SyncOutcome third = new SkeinClientSession(platform, e -> {}).run(thirdGrant);

            assertThat(third.isSuccess()).isTrue();
            assertThat(Files.exists(gameDir.resolve("mods").resolve("example.jar"))).isFalse();
        }
    }

    @Test
    void unchangedFileNeverFetchedBySkeinIsNeverQuarantinedEvenAfterThePackDropsIt(@TempDir Path root)
            throws Exception {
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");
        try (LocalPackServer server = new LocalPackServer(packDir)) {
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);

            // The player already has this exact file *before ever syncing* -- byte-
            // identical to what the pack declares (LocalPackServer writes "hello-mod"
            // into mods/example.jar). The first sync's diff classifies it `unchanged`:
            // never fetched, never committed by Skein, even though it is a "desired"
            // path at diff time. The ledger must never claim Skein installed it.
            Path clientMod = gameDir.resolve("mods").resolve("example.jar");
            Files.createDirectories(clientMod.getParent());
            Files.writeString(clientMod, "hello-mod");

            SessionGrantS2C grant = SessionGrantS2C.granted(server.endpoint(), "", "1.0.0", server.rootHash());
            SyncOutcome first = new SkeinClientSession(platform, e -> {}).run(grant);
            assertThat(first.isSuccess()).isTrue();
            assertThat(Files.readString(clientMod)).isEqualTo("hello-mod");

            // The pack later drops the mod entirely.
            server.pack().deleteFile(new RelativePath("mods/example.jar"));
            SessionGrantS2C secondGrant = SessionGrantS2C.granted(
                    server.endpoint(), "", "1.0.0", server.rootHash());
            SyncOutcome second = new SkeinClientSession(platform, e -> {}).run(secondGrant);

            assertThat(second.isSuccess()).isTrue();
            // Skein never installed this file (it was always `unchanged`), so it must
            // never be touched -- same guarantee as a player-added mod.
            assertThat(Files.exists(clientMod)).isTrue();
            assertThat(Files.readString(clientMod)).isEqualTo("hello-mod");
        }
    }

    @Test
    void ledgerSurvivesADownloadFailureThatHappensAfterTheDiffHasAlreadyRun(@TempDir Path root) throws Exception {
        Path packDir = root.resolve("server-pack");
        Path gameDir = root.resolve("client");
        try (LocalPackServer server = new LocalPackServer(packDir)) {
            FakeClientPlatform platform = new FakeClientPlatform(gameDir);
            SessionGrantS2C grant = SessionGrantS2C.granted(server.endpoint(), "", "1.0.0", server.rootHash());

            SyncOutcome first = new SkeinClientSession(platform, e -> {}).run(grant);
            assertThat(first.isSuccess()).isTrue();

            InstalledPathsCache ledger = new InstalledPathsCache(platform.paths().skeinDir());
            var afterFirstSync = ledger.read();
            assertThat(afterFirstSync).contains(new RelativePath("mods/example.jar"));

            // Add a second mod so the next sync's diff plans a genuine fetch (a real
            // ADD, distinct from the "unreachable server" trick used above, which
            // fails during manifest resolution -- before DiffComputed is ever
            // published). Then corrupt that file's bytes directly on the pack
            // directory, bypassing ManagedPack.addFile (which would rehash and keep
            // everything consistent). This leaves the manifest advertising the
            // *original* hash/size -- still fully resolvable -- while the bytes
            // SkeinHttpServer actually streams for that hash no longer match. The
            // download therefore fails hash verification (DefaultFileDownloader),
            // *after* the diff already ran and DiffComputed was already published.
            byte[] original = "new-mod-content!".getBytes(StandardCharsets.UTF_8);
            RelativePath newMod = new RelativePath("mods/new.jar");
            server.pack().addFile(newMod, original);
            byte[] corrupted = original.clone();
            corrupted[0] ^= 0x7F; // flip bits so the hash changes; length is unchanged
            Files.write(server.pack().packDir().resolve("mods").resolve("new.jar"), corrupted);

            SessionGrantS2C secondGrant = SessionGrantS2C.granted(
                    server.endpoint(), "", "1.0.0", server.rootHash());
            SyncOutcome failed = new SkeinClientSession(platform, e -> {}).run(secondGrant);

            assertThat(failed.isSuccess()).isFalse();
            assertThat(ledger.read()).isEqualTo(afterFirstSync);
            assertThat(Files.exists(gameDir.resolve("mods").resolve("new.jar"))).isFalse();

            // Fix the corruption and confirm the next sync succeeds and picks up
            // both the new mod and (still) the untouched original.
            Files.write(server.pack().packDir().resolve("mods").resolve("new.jar"), original);
            SessionGrantS2C thirdGrant = SessionGrantS2C.granted(
                    server.endpoint(), "", "1.0.0", server.rootHash());
            SyncOutcome third = new SkeinClientSession(platform, e -> {}).run(thirdGrant);

            assertThat(third.isSuccess()).isTrue();
            assertThat(Files.exists(gameDir.resolve("mods").resolve("new.jar"))).isTrue();
            assertThat(Files.exists(gameDir.resolve("mods").resolve("example.jar"))).isTrue();
        }
    }
}
