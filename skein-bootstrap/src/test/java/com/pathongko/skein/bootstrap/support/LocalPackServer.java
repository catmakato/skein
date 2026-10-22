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

        // The manifest bakes each file's absolute download URL in at generation
        // time (ManifestGenerator resolves fileBaseUrl + hash eagerly), so the real
        // ephemeral port must be known *before* building the ManagedPack. Bind the
        // server first (against a supplier reading a field set right after), then
        // build the manifest with that port, then let requests start arriving.
        this.server = new SkeinHttpServer(this::currentPack, Optional.empty(),
                Optional.empty(), new InetSocketAddress("127.0.0.1", 0));
        this.port = server.start();

        String baseUrl = "http://127.0.0.1:" + port + "/files/";
        this.pack = new ManagedPack(packDir, "test-pack", PackVersion.of("1.0.0"),
                HashAlgorithm.SHA256, baseUrl, SyncComponents.CATEGORY_DIRS,
                new MessageDigestHashService(), new JsonManifestCodec());
    }

    private com.pathongko.skein.server.manifest.GeneratedPack currentPack() {
        return pack.current();
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
        server.stop();
    }
}
