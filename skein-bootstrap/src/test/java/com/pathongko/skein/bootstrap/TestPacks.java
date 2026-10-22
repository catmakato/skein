package com.pathongko.skein.bootstrap;

import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.server.manage.ManagedPack;
import com.pathongko.skein.server.manifest.GeneratedPack;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small helpers building a real {@link GeneratedPack} for tests, without a live HTTP server. */
final class TestPacks {

    private TestPacks() {
    }

    static GeneratedPack smallPack() {
        try {
            Path packDir = Files.createTempDirectory("skein-test-pack");
            Files.createDirectories(packDir.resolve("mods"));
            Files.write(packDir.resolve("mods").resolve("example.jar"), "hello-mod".getBytes());

            ManagedPack pack = new ManagedPack(packDir, "test-pack", PackVersion.of("1.0.0"),
                    HashAlgorithm.SHA256, "http://x/files/", SyncComponents.CATEGORY_DIRS,
                    new MessageDigestHashService(), new JsonManifestCodec());
            return pack.current();
        } catch (IOException e) {
            throw new UncheckedIOException("failed building test pack", e);
        }
    }
}
