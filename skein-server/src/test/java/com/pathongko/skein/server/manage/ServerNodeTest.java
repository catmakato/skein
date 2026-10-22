package com.pathongko.skein.server.manage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.server.http.SkeinHttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
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

    @Test
    void whenPersistingTheMarkerFileFails_theInMemoryFlagIsNotChanged(@TempDir Path dir) throws IOException {
        Path packDir = dir.resolve("pack");
        ServerNode node = node(packDir);

        // Enable the gate successfully
        node.setWhitelistGateEnabled(true);
        assertThat(node.whitelistGateEnabled()).isTrue();

        // Replace the marker file with a non-empty directory so deletion fails
        Path markerPath = packDir.resolve(".whitelist-gate-enabled");
        Files.delete(markerPath);
        Files.createDirectory(markerPath);
        // Add a file inside to make the directory non-empty, so deleteIfExists fails
        Files.createFile(markerPath.resolve("dummy"));

        // Try to disable the gate. The directory is non-empty so deleteIfExists will fail
        try {
            node.setWhitelistGateEnabled(false);
        } catch (UncheckedIOException e) {
            // Expected: deleteIfExists fails on non-empty directory
        }

        // In-memory state should remain true because the disk operation failed
        assertThat(node.whitelistGateEnabled()).isTrue();
    }
}
