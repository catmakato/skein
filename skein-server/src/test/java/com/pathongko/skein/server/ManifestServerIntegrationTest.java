package com.pathongko.skein.server;

import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.PackVersion;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.manifest.CategoryIndex;
import com.pathongko.skein.core.manifest.IndexRef;
import com.pathongko.skein.core.manifest.JsonManifestCodec;
import com.pathongko.skein.core.manifest.RootManifest;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.server.http.SkeinHttpServer;
import com.pathongko.skein.server.manifest.GeneratedPack;
import com.pathongko.skein.server.manifest.ManifestGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestServerIntegrationTest {

    private static final HashService HASH = new MessageDigestHashService();
    private static final JsonManifestCodec CODEC = new JsonManifestCodec();

    @TempDir
    Path pack;

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private GeneratedPack generate(int port) throws Exception {
        Files.createDirectories(pack.resolve("mods"));
        Files.writeString(pack.resolve("mods/a.jar"), "content-a");
        Files.writeString(pack.resolve("mods/b.jar"), "content-b-longer");
        ManifestGenerator generator = new ManifestGenerator(HASH, CODEC, HashAlgorithm.SHA256,
                "http://127.0.0.1:" + port + "/files/");
        Map<FileCategory, String> dirs = new EnumMap<>(FileCategory.class);
        dirs.put(FileCategory.MOD, "mods");
        return generator.generate(pack, "test", PackVersion.of("1.0.0"), Instant.EPOCH, dirs);
    }

    @Test
    void generatesIndicesAndContentMap() throws Exception {
        GeneratedPack gp = generate(0);
        assertThat(gp.contentByHash()).hasSize(2);
        assertThat(gp.root().indices()).singleElement()
                .extracting(IndexRef::entryCount).isEqualTo(2);
    }

    @Test
    void servesManifestAndFilesOverHttp() throws Exception {
        int port = freePort();
        GeneratedPack gp = generate(port);
        SkeinHttpServer server = new SkeinHttpServer(gp, Optional.empty(),
                new InetSocketAddress("127.0.0.1", port));
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();

            HttpResponse<byte[]> root = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/manifest/root")).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(root.statusCode()).isEqualTo(200);
            RootManifest served = CODEC.decodeRoot(root.body());
            assertThat(served).isEqualTo(gp.root());

            CategoryIndex index = CODEC.decodeIndex(client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/manifest/index/MOD")).build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body());
            String hash = index.entries().get(0).hash().value();

            HttpResponse<byte[]> file = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/files/" + hash)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(file.statusCode()).isEqualTo(200);
            assertThat(HASH.hash(file.body(), HashAlgorithm.SHA256).value()).isEqualTo(hash);
        } finally {
            server.stop();
        }
    }

    @Test
    void aStalledDownloadDoesNotStarveOtherClients() throws Exception {
        int port = freePort();
        Files.createDirectories(pack.resolve("mods"));
        byte[] big = new byte[8 * 1024 * 1024];
        new Random(42).nextBytes(big);
        Files.write(pack.resolve("mods/big.jar"), big);

        ManifestGenerator generator = new ManifestGenerator(HASH, CODEC, HashAlgorithm.SHA256,
                "http://127.0.0.1:" + port + "/files/");
        Map<FileCategory, String> dirs = new EnumMap<>(FileCategory.class);
        dirs.put(FileCategory.MOD, "mods");
        GeneratedPack gp = generator.generate(pack, "test", PackVersion.of("1.0.0"), Instant.EPOCH, dirs);
        CategoryIndex index = CODEC.decodeIndex(gp.encodedIndices().get(FileCategory.MOD));
        String bigHash = index.entries().get(0).hash().value();

        SkeinHttpServer server = new SkeinHttpServer(gp, Optional.empty(),
                new InetSocketAddress("127.0.0.1", port));
        server.start();
        try (Socket slowClient = new Socket("127.0.0.1", port)) {
            // Send a request for the large file but never read the response body:
            // once the OS socket buffers fill, the server's write blocks. A player
            // who alt-tabs, has a flaky connection, or is just slow reproduces this
            // for real; a non-reading raw socket reproduces it deterministically.
            slowClient.getOutputStream().write(
                    ("GET /files/" + bigHash + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
            slowClient.getOutputStream().flush();
            Thread.sleep(300);

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/manifest/root"))
                            .timeout(Duration.ofSeconds(3))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).isEqualTo(200);
        } finally {
            server.stop();
        }
    }

    @Test
    void rejectsRequestsWithoutTheToken() throws Exception {
        int port = freePort();
        GeneratedPack gp = generate(port);
        SkeinHttpServer server = new SkeinHttpServer(gp, Optional.of("secret"),
                new InetSocketAddress("127.0.0.1", port));
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            URI uri = URI.create("http://127.0.0.1:" + port + "/manifest/root");

            int unauthorized = client.send(HttpRequest.newBuilder(uri).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            int authorized = client.send(
                    HttpRequest.newBuilder(uri).header("Authorization", "Bearer secret").build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();

            assertThat(unauthorized).isEqualTo(401);
            assertThat(authorized).isEqualTo(200);
        } finally {
            server.stop();
        }
    }
}
