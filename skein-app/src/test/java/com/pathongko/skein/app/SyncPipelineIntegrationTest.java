package com.pathongko.skein.app;

import com.pathongko.skein.app.apply.DefaultApplyService;
import com.pathongko.skein.app.download.DefaultDownloadService;
import com.pathongko.skein.core.diff.ChangeType;
import com.pathongko.skein.core.diff.PlannedFetch;
import com.pathongko.skein.core.diff.SyncPlan;
import com.pathongko.skein.core.domain.DownloadSource;
import com.pathongko.skein.core.domain.FileCategory;
import com.pathongko.skein.core.domain.FileEntry;
import com.pathongko.skein.core.domain.HashAlgorithm;
import com.pathongko.skein.core.domain.RelativePath;
import com.pathongko.skein.core.domain.SyncPolicy;
import com.pathongko.skein.core.hash.MessageDigestHashService;
import com.pathongko.skein.core.port.FileManager;
import com.pathongko.skein.core.port.HashService;
import com.pathongko.skein.core.sync.ProgressCallback;
import com.pathongko.skein.core.sync.port.ApplyService;
import com.pathongko.skein.core.sync.port.DownloadService;
import com.pathongko.skein.storage.file.DefaultFileManager;
import com.pathongko.skein.transfer.download.DefaultFileDownloader;
import com.pathongko.skein.transfer.download.FileDownloader;
import com.pathongko.skein.transfer.http.HttpRangeClient;
import com.pathongko.skein.transfer.http.JdkHttpRangeClient;
import com.pathongko.skein.transfer.retry.DefaultRetryPolicy;
import com.pathongko.skein.transfer.retry.ExponentialBackoff;
import com.pathongko.skein.transfer.retry.Jitter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the whole shared pipeline over a real (localhost) HTTP server:
 * JDK HTTP adapter -> downloader (hash verify) -> download service -> apply
 * service -> committed into the instance. Uses only the JDK's HttpServer, so it
 * needs no external services.
 */
class SyncPipelineIntegrationTest {

    private static final HashService HASH = new MessageDigestHashService();

    private HttpServer server;
    private URI uri;
    private byte[] content;

    @TempDir
    Path root;

    @BeforeEach
    void startServer() throws Exception {
        content = new byte[5000];
        new Random(7).nextBytes(content);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            String range = exchange.getRequestHeaders().getFirst("Range");
            byte[] body;
            int status;
            if (range == null) {
                body = content;
                status = 200;
            } else {
                int start = Integer.parseInt(range.replace("bytes=", "").split("-")[0]);
                body = Arrays.copyOfRange(content, start, content.length);
                status = 206;
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/file");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void downloadsOverHttpAndCommitsIntoInstance() {
        HttpRangeClient client = new JdkHttpRangeClient(
                HttpClient.newHttpClient(), Duration.ofSeconds(30), Optional::empty);
        FileDownloader downloader = new DefaultFileDownloader(client, HASH,
                new DefaultRetryPolicy(5, new ExponentialBackoff(
                        Duration.ofMillis(1), Duration.ofMillis(1), Jitter.none())),
                com.pathongko.skein.transfer.download.Sleeper.NONE);

        FileManager fileManager = new DefaultFileManager(root);
        DownloadService downloadService = new DefaultDownloadService(downloader, fileManager);
        ApplyService applyService = new DefaultApplyService(fileManager);

        FileEntry entry = new FileEntry(new RelativePath("mods/real.jar"), content.length,
                HASH.hash(content, HashAlgorithm.SHA256), FileCategory.MOD, SyncPolicy.mandatory(),
                Set.of(new DownloadSource(uri, 0)));
        SyncPlan plan = new SyncPlan(List.of(new PlannedFetch(entry, ChangeType.ADD)), List.of(), List.of());

        long[] lastDone = {0};
        ProgressCallback progress = (done, total) -> lastDone[0] = done;
        downloadService.execute(plan, progress);
        applyService.apply(plan);

        assertThat(lastDone[0]).isEqualTo(content.length);
        assertThat(Files.exists(root.resolve("mods/real.jar"))).isTrue();
    }
}
