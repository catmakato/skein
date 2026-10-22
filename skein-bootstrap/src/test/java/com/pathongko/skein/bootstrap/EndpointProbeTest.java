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
    void rejectsANonSkeinServerReturningNonManifestBody() throws Exception {
        try (PlainHttp server = new PlainHttp(200, "not a manifest")) {
            assertThat(probe.probe(server.baseUrl(), Optional.empty())).isFalse();
        }
    }

    @Test
    void rejectsA404() throws Exception {
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
                try (var os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
